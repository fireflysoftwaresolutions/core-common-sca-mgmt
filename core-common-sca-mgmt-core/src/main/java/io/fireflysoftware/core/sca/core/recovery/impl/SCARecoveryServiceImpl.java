/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fireflysoftware.core.sca.core.recovery.impl;

import io.fireflysoftware.core.sca.core.recovery.IssuedRecoveryChallenge;
import io.fireflysoftware.core.sca.core.recovery.RecoveryAdmissionPort;
import io.fireflysoftware.core.sca.core.recovery.RecoveryChallengeReference;
import io.fireflysoftware.core.sca.core.recovery.RecoveryDeniedException;
import io.fireflysoftware.core.sca.core.recovery.RecoveryExecutionVerifier;
import io.fireflysoftware.core.sca.core.recovery.RecoveryFactorProvider;
import io.fireflysoftware.core.sca.core.recovery.RecoveryProperties;
import io.fireflysoftware.core.sca.core.recovery.RecoveryRejectionAuditPort;
import io.fireflysoftware.core.sca.core.recovery.RecoveryUnavailableException;
import io.fireflysoftware.core.sca.core.recovery.SCARecoveryService;
import io.fireflysoftware.core.sca.core.recovery.TrustedRecoveryContext;
import io.fireflysoftware.core.sca.core.recovery.VerifiedRecoveryProof;
import io.fireflysoftware.core.sca.interfaces.recovery.ConsumeRecoveryAuthorizationRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.CreateRecoveryOperationRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.IssueRecoveryChallengeRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.RecordRecoveryExecutionRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryAction;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryAuthorizationReceipt;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryBinding;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryChallengeResponse;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryExecutionOutcome;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryFactorCategory;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryOperationResponse;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryProofKind;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryVerificationResponse;
import io.fireflysoftware.core.sca.interfaces.recovery.VerifyRecoveryChallengeRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.ReserveRecoveryVerificationRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryVerificationReservation;
import io.fireflysoftware.core.sca.interfaces.recovery.CompleteRecoveryVerificationRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.VerifyRecoveryAuthorizationRequest;
import io.fireflysoftware.core.sca.models.entities.SCARecoveryAudit;
import io.fireflysoftware.core.sca.models.entities.SCARecoveryChallenge;
import io.fireflysoftware.core.sca.models.entities.SCARecoveryOperation;
import io.fireflysoftware.core.sca.models.repositories.SCARecoveryAuditRepository;
import io.fireflysoftware.core.sca.models.repositories.SCARecoveryChallengeRepository;
import io.fireflysoftware.core.sca.models.repositories.SCARecoveryOperationRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Function;

@Service
@ConditionalOnProperty(name = "firefly.sca.recovery.enabled", havingValue = "true")
public class SCARecoveryServiceImpl implements SCARecoveryService {
    private final SCARecoveryOperationRepository operations;
    private final SCARecoveryChallengeRepository challenges;
    private final SCARecoveryAuditRepository audits;
    private final TransactionalOperator transactions;
    private final RecoveryAdmissionPort admission;
    private final RecoveryFactorProvider factors;
    private final RecoveryExecutionVerifier executor;
    private final RecoveryProperties properties;
    private final RecoveryRejectionAuditPort rejectionAudit;

    public SCARecoveryServiceImpl(SCARecoveryOperationRepository operations,
            SCARecoveryChallengeRepository challenges, SCARecoveryAuditRepository audits,
            @Qualifier("recoveryTransactionOperator") TransactionalOperator transactions,
            RecoveryAdmissionPort admission, RecoveryFactorProvider factors,
            RecoveryExecutionVerifier executor, RecoveryProperties properties, RecoveryRejectionAuditPort rejectionAudit) {
        properties.validate();
        this.operations = operations;
        this.challenges = challenges;
        this.audits = audits;
        this.transactions = transactions;
        this.admission = admission;
        this.factors = factors;
        this.executor = executor;
        this.properties = properties;
        this.rejectionAudit = rejectionAudit;
        if (!admission.isQualified() || !factors.isQualified() || !executor.isQualified() || !rejectionAudit.isQualified()) {
            throw new IllegalStateException("Recovery requires qualified admission, factor, execution and durable rejection audit capabilities");
        }
    }

    @Override
    public Mono<RecoveryOperationResponse> createRecoveryOperation(CreateRecoveryOperationRequest request) {
        return Mono.defer(() -> {
            require(request != null && validKey(request.idempotencyKey()));
            return admitted(request.binding(), RecoveryAction.CREATE, context ->
                    transaction(() -> operations.databaseNow().flatMap(now -> {
                        checkContext(context, request.binding(), RecoveryAction.CREATE, now);
                        return existingCreation(request, context).switchIfEmpty(Mono.defer(() -> {
                            SCARecoveryOperation operation = new SCARecoveryOperation();
                            operation.setId(UUID.randomUUID());
                            setBinding(operation, context.binding());
                            operation.setOwnerLinkId(context.ownerLinkId());
                            operation.setIdentityLinkAttestationId(context.identityLinkAttestationId());
                            operation.setLockAttestationId(context.lockAttestationId());
                            operation.setCreateKey(request.idempotencyKey());
                            operation.setState("OPEN");
                            operation.setCreatedAt(now);
                            operation.setExpiresAt(earliest(now.plus(properties.getOperationTtl()), context.validUntil()));
                            operation.setAttemptCount(0);
                            operation.setMaxAttempts(properties.getMaxAttempts());
                            operation.setChallengeCount(0);
                            operation.setMaxChallenges(properties.getMaxChallenges());
                            return operations.save(operation)
                                    .flatMap(saved -> audit(saved, "CREATED", now).thenReturn(response(saved)));
                        }));
                    })).onErrorResume(DuplicateKeyException.class, ignored -> existingCreation(request, context)
                            .switchIfEmpty(Mono.error(new RecoveryDeniedException()))));
        });
    }

    @Override
    public Mono<RecoveryChallengeResponse> issueRecoveryChallenge(UUID operationId, IssueRecoveryChallengeRequest request) {
        return Mono.defer(() -> {
            require(operationId != null && request != null && request.category() != null);
            return admitted(request.binding(), RecoveryAction.ISSUE_CHALLENGE, context ->
                    transaction(() -> operations.databaseNow().flatMap(now -> load(operationId, context).flatMap(operation -> {
                        checkContext(context, request.binding(), RecoveryAction.ISSUE_CHALLENGE, now);
                        checkOpen(operation, now);
                        require(operation.getChallengeCount() < operation.getMaxChallenges());
                        UUID reservationId = UUID.randomUUID();
                        operation.setChallengeCount(operation.getChallengeCount() + 1);
                        return operations.save(operation)
                                .then(audit(operation, "CHALLENGE_RESERVED", now, reservationId))
                                .thenReturn(reservationId);
                    })))
                    .flatMap(reservationId -> external(() -> factors.issue(context, operationId, reservationId, request.category()))
                            .flatMap(issued -> admit(request.binding(), RecoveryAction.ISSUE_CHALLENGE).flatMap(freshContext -> transaction(() -> operations.databaseNow().flatMap(now ->
                                    load(operationId, freshContext).flatMap(operation -> {
                                        checkContext(freshContext, request.binding(), RecoveryAction.ISSUE_CHALLENGE, now);
                                        checkOpen(operation, now);
                                        validateIssued(operationId, reservationId, request, issued, now);
                                        return audits.existsByOperationIdAndEventTypeAndReservationId(
                                                operationId, "CHALLENGE_RESERVED", reservationId).flatMap(reserved -> {
                                            require(reserved);
                                            SCARecoveryChallenge challenge = new SCARecoveryChallenge();
                                            challenge.setId(UUID.randomUUID());
                                            challenge.setOperationId(operationId);
                                            challenge.setIssueReservationId(reservationId);
                                            challenge.setCategory(issued.category().name());
                                            challenge.setProviderId(issued.providerId());
                                            challenge.setEnrollmentId(issued.enrollmentId());
                                            challenge.setIndependenceId(issued.independenceId());
                                            challenge.setProviderChallengeId(issued.providerChallengeId());
                                            challenge.setCreatedAt(now);
                                            challenge.setExpiresAt(earliest(operation.getExpiresAt(), issued.expiresAt(),
                                                    now.plus(properties.getChallengeTtl())));
                                            challenge.setAttemptCount(0);
                                            challenge.setMaxAttempts(properties.getMaxChallengeAttempts());
                                            challenge.setVerified(false);
                                            return operations.save(operation).then(challenges.save(challenge))
                                                    .flatMap(saved -> audit(operation, "CHALLENGE_ISSUED", now, reservationId)
                                                            .thenReturn(new RecoveryChallengeResponse(saved.getId(),
                                                                    issued.category(), saved.getExpiresAt(), issued.maskedDestination())));
                                        });
                                    })))))));
        });
    }

    @Override
    public Mono<RecoveryVerificationResponse> verifyRecoveryChallenge(UUID operationId, VerifyRecoveryChallengeRequest request) {
        return Mono.defer(() -> {
            require(operationId != null && request != null && request.challengeId() != null
                    && request.proofKind() != null && request.response() != null
                    && !request.response().isBlank() && request.response().length() <= 16384);
            return admitted(request.binding(), RecoveryAction.VERIFY_CHALLENGE, context ->
                    reserveVerification(operationId, request, context)
                            .flatMap(reference -> external(() -> factors.verify(context, reference,
                                            request.proofKind(), request.response()))
                                    .flatMap(proof -> admit(request.binding(), RecoveryAction.VERIFY_CHALLENGE)
                                            .flatMap(freshContext -> persistProof(operationId, request, freshContext,
                                                    reference.reservationId(), proof)))
                                    .onErrorResume(RecoveryDeniedException.class, ignored ->
                                            recordReservedRejection(operationId, request.challengeId(), context, reference.reservationId())
                                                    .thenReturn(acknowledged(operationId))))
                            .onErrorResume(RecoveryDeniedException.class, ignored ->
                                    rejected(context, RecoveryAction.VERIFY_CHALLENGE).thenReturn(acknowledged(operationId))));
        });
    }

    @Override
    public Mono<RecoveryVerificationReservation> reserveRecoveryVerification(UUID operationId, ReserveRecoveryVerificationRequest request) {
        return Mono.defer(() -> {
            require(operationId != null && request != null && request.challengeId() != null);
            return admitted(request.binding(), RecoveryAction.RESERVE_VERIFICATION, context ->
                    transaction(() -> operations.databaseNow().flatMap(now -> load(operationId, context)
                            .flatMap(operation -> challenges.findById(request.challengeId())
                                    .switchIfEmpty(Mono.error(new RecoveryDeniedException())).flatMap(challenge -> {
                                checkContext(context, request.binding(), RecoveryAction.RESERVE_VERIFICATION, now);
                                require("OPEN".equals(operation.getState()) && now.isBefore(operation.getExpiresAt())
                                        && operationId.equals(challenge.getOperationId()) && !Boolean.TRUE.equals(challenge.getVerified())
                                        && now.isBefore(challenge.getExpiresAt()));
                                if (challenge.getVerificationReservationId() != null) {
                                    return audits.existsByOperationIdAndEventTypeAndReservationId(operationId, "PROOF_RESERVED",
                                                    challenge.getVerificationReservationId())
                                            .filter(Boolean::booleanValue).switchIfEmpty(Mono.error(new RecoveryDeniedException()))
                                            .thenReturn(reservation(challenge));
                                }
                                checkOpen(operation, now);
                                checkChallenge(challenge, operationId, now);
                                UUID reservationId = UUID.randomUUID();
                                operation.setAttemptCount(operation.getAttemptCount() + 1);
                                challenge.setAttemptCount(challenge.getAttemptCount() + 1);
                                challenge.setVerificationReservationId(reservationId);
                                challenge.setVerificationReservedAt(now);
                                return operations.save(operation).then(challenges.save(challenge))
                                        .flatMap(saved -> audit(operation, "PROOF_RESERVED", now, reservationId)
                                                .thenReturn(reservation(saved)));
                            })))).flux().singleOrEmpty());
        });
    }

    @Override
    public Mono<RecoveryVerificationResponse> completeRecoveryVerification(UUID operationId, CompleteRecoveryVerificationRequest request) {
        return Mono.defer(() -> {
            require(operationId != null && request != null && request.challengeId() != null && request.reservationId() != null
                    && request.attestation() != null && !request.attestation().isBlank() && request.attestation().length() <= 16384);
            return admitted(request.binding(), RecoveryAction.COMPLETE_VERIFICATION, context ->
                    transaction(() -> operations.databaseNow().flatMap(now -> load(operationId, context)
                            .flatMap(operation -> challenges.findById(request.challengeId())
                                    .switchIfEmpty(Mono.error(new RecoveryDeniedException())).flatMap(challenge -> {
                                checkContext(context, request.binding(), RecoveryAction.COMPLETE_VERIFICATION, now);
                                require(operationId.equals(challenge.getOperationId()));
                                if (challenge.getVerificationReservationId() == null) {
                                    return audits.existsByOperationIdAndEventTypeAndReservationId(operationId, "PROOF_ACCEPTED", request.reservationId())
                                            .zipWith(audits.existsByOperationIdAndEventTypeAndReservationId(operationId, "PROOF_REJECTED", request.reservationId()))
                                            .filter(events -> events.getT1() || events.getT2())
                                            .switchIfEmpty(Mono.error(new RecoveryDeniedException()))
                                            .then(Mono.<RecoveryChallengeReference>empty());
                                }
                                require(request.reservationId().equals(challenge.getVerificationReservationId())
                                        && "OPEN".equals(operation.getState()) && now.isBefore(operation.getExpiresAt())
                                        && !Boolean.TRUE.equals(challenge.getVerified()) && now.isBefore(challenge.getExpiresAt()));
                                return audits.existsByOperationIdAndEventTypeAndReservationId(operationId, "PROOF_RESERVED", request.reservationId())
                                        .filter(Boolean::booleanValue).switchIfEmpty(Mono.error(new RecoveryDeniedException()))
                                        .thenReturn(reference(challenge, context.binding()));
                            })))).flatMap(reference -> external(() -> factors.verify(context, reference,
                                            RecoveryProofKind.PROVIDER_ATTESTATION, request.attestation()))
                                    .flatMap(proof -> admit(request.binding(), RecoveryAction.COMPLETE_VERIFICATION)
                                            .flatMap(fresh -> completeProof(operationId,
                                                    new VerifyRecoveryChallengeRequest(request.binding(), request.challengeId(),
                                                            RecoveryProofKind.PROVIDER_ATTESTATION, request.attestation()),
                                                    fresh, request.reservationId(), proof, RecoveryAction.COMPLETE_VERIFICATION)
                                                    .onErrorResume(DuplicateKeyException.class, ignored -> completeProof(operationId,
                                                            new VerifyRecoveryChallengeRequest(request.binding(), request.challengeId(),
                                                                    RecoveryProofKind.PROVIDER_ATTESTATION, request.attestation()),
                                                            fresh, request.reservationId(), null, RecoveryAction.COMPLETE_VERIFICATION)))))
                            .switchIfEmpty(Mono.just(acknowledged(operationId)))
                            .onErrorResume(RecoveryDeniedException.class, ignored -> rejected(context, RecoveryAction.COMPLETE_VERIFICATION)
                                    .thenReturn(acknowledged(operationId))));
        });
    }

    @Override
    public Mono<RecoveryAuthorizationReceipt> verifyRecoveryAuthorization(UUID operationId, VerifyRecoveryAuthorizationRequest request) {
        return Mono.defer(() -> {
            require(operationId != null && request != null && request.executionId() != null && request.expiresAt() != null);
            return admitted(request.binding(), RecoveryAction.VERIFY_AUTHORIZATION, context ->
                    transaction(() -> operations.databaseNow().flatMap(now -> load(operationId, context).flatMap(operation -> {
                        checkContext(context, request.binding(), RecoveryAction.VERIFY_AUTHORIZATION, now);
                        require("CONSUMED".equals(operation.getState()) && "PENDING".equals(operation.getExecutionOutcome())
                                && request.executionId().equals(operation.getExecutionId())
                                && request.expiresAt().equals(operation.getReceiptExpiresAt())
                                && now.isBefore(operation.getReceiptExpiresAt()));
                        return audits.existsByOperationIdAndEventTypeAndExecutionId(operationId, "CONSUMED", request.executionId())
                                .filter(Boolean::booleanValue).switchIfEmpty(Mono.error(new RecoveryDeniedException()))
                                .then(operations.save(operation))
                                .flatMap(saved -> audit(saved, "AUTHORIZATION_VERIFIED", now).thenReturn(receipt(saved)));
                    }))).flux().singleOrEmpty());
        });
    }

    private RecoveryVerificationReservation reservation(SCARecoveryChallenge challenge) {
        return new RecoveryVerificationReservation(challenge.getOperationId(), challenge.getId(),
                challenge.getVerificationReservationId(), challenge.getProviderChallengeId(),
                RecoveryFactorCategory.valueOf(challenge.getCategory()), challenge.getExpiresAt());
    }

    private Mono<RecoveryChallengeReference> reserveVerification(UUID operationId, VerifyRecoveryChallengeRequest request,
            TrustedRecoveryContext context) {
        return transaction(() -> operations.databaseNow().flatMap(now -> load(operationId, context)
                .flatMap(operation -> challenges.findById(request.challengeId())
                        .switchIfEmpty(Mono.error(new RecoveryDeniedException())).flatMap(challenge -> {
                    checkContext(context, request.binding(), RecoveryAction.VERIFY_CHALLENGE, now);
                    checkOpen(operation, now);
                    checkChallenge(challenge, operationId, now);
                    require(challenge.getVerificationReservationId() == null
                            && (!"KNOWLEDGE".equals(challenge.getCategory())
                            || request.proofKind() == RecoveryProofKind.PROVIDER_ATTESTATION));
                    UUID reservationId = UUID.randomUUID();
                    operation.setAttemptCount(operation.getAttemptCount() + 1);
                    challenge.setAttemptCount(challenge.getAttemptCount() + 1);
                    challenge.setVerificationReservationId(reservationId);
                    challenge.setVerificationReservedAt(now);
                    return operations.save(operation).then(challenges.save(challenge))
                            .flatMap(saved -> audit(operation, "PROOF_RESERVED", now, reservationId)
                                    .thenReturn(reference(saved, context.binding())));
                }))));
    }

    private Mono<Void> recordReservedRejection(UUID operationId, UUID challengeId,
            TrustedRecoveryContext context, UUID reservationId) {
        return transaction(() -> operations.databaseNow().flatMap(now -> load(operationId, context)
                .flatMap(operation -> challenges.findById(challengeId)
                        .filter(challenge -> operationId.equals(challenge.getOperationId())
                                && reservationId.equals(challenge.getVerificationReservationId()))
                        .switchIfEmpty(Mono.error(new RecoveryDeniedException()))
                        .flatMap(challenge -> audit(operation, "PROOF_REJECTED", now, reservationId)))));
    }

    private Mono<RecoveryVerificationResponse> persistProof(UUID operationId, VerifyRecoveryChallengeRequest request,
            TrustedRecoveryContext context, UUID reservationId, VerifiedRecoveryProof proof) {
        return completeProof(operationId, request, context, reservationId, proof)
                .onErrorResume(DuplicateKeyException.class, ignored ->
                        completeProof(operationId, request, context, reservationId, null));
    }

    private Mono<RecoveryVerificationResponse> completeProof(UUID operationId, VerifyRecoveryChallengeRequest request,
            TrustedRecoveryContext context, UUID reservationId, VerifiedRecoveryProof proof) {
        return completeProof(operationId, request, context, reservationId, proof, RecoveryAction.VERIFY_CHALLENGE);
    }

    private Mono<RecoveryVerificationResponse> completeProof(UUID operationId, VerifyRecoveryChallengeRequest request,
            TrustedRecoveryContext context, UUID reservationId, VerifiedRecoveryProof proof, RecoveryAction action) {
        return transaction(() -> operations.databaseNow().flatMap(now -> load(operationId, context)
                .flatMap(operation -> challenges.findById(request.challengeId())
                        .switchIfEmpty(Mono.error(new RecoveryDeniedException())).flatMap(challenge -> {
                    checkContext(context, request.binding(), action, now);
                    require("OPEN".equals(operation.getState()) && now.isBefore(operation.getExpiresAt())
                            && operation.getAttemptCount() <= operation.getMaxAttempts()
                            && operationId.equals(challenge.getOperationId()) && !Boolean.TRUE.equals(challenge.getVerified())
                            && now.isBefore(challenge.getExpiresAt())
                            && reservationId.equals(challenge.getVerificationReservationId()));
                    boolean accepted = proofMatches(proof, challenge, context.binding(), now);
                    challenge.setVerificationReservationId(null);
                    challenge.setVerificationReservedAt(null);
                    if (accepted) {
                        challenge.setVerified(true);
                        challenge.setAttestationId(proof.attestationId());
                        challenge.setProofExpiresAt(earliest(proof.expiresAt(), challenge.getExpiresAt()));
                    }
                    return operations.save(operation).flatMap(savedOperation -> challenges.save(challenge)
                            .then(audit(savedOperation, accepted ? "PROOF_ACCEPTED" : "PROOF_REJECTED", now, reservationId))
                            .then(challenges.existsByOperationIdAndVerificationReservationIdIsNotNull(operationId))
                            .flatMap(pending -> {
                                if (!accepted && !pending && savedOperation.getAttemptCount().equals(savedOperation.getMaxAttempts())) {
                                    return challenges.findAllByOperationId(operationId).collectList()
                                            .flatMap(proofs -> operations.databaseNow().flatMap(currentNow -> {
                                                if (proofExpiry(proofs, currentNow) == null) {
                                                    savedOperation.setState("EXHAUSTED");
                                                    return operations.save(savedOperation).thenReturn(acknowledged(operationId));
                                                }
                                                return Mono.just(acknowledged(operationId));
                                            }));
                                }
                                return Mono.just(acknowledged(operationId));
                            }));
                }))));
    }

    @Override
    public Mono<RecoveryAuthorizationReceipt> consumeRecoveryAuthorization(UUID operationId,
            ConsumeRecoveryAuthorizationRequest request) {
        return Mono.defer(() -> {
            require(operationId != null && request != null && validKey(request.idempotencyKey()));
            return admitted(request.binding(), RecoveryAction.CONSUME, context ->
                    transaction(() -> operations.databaseNow().flatMap(now -> load(operationId, context)
                            .flatMap(operation -> {
                                checkContext(context, request.binding(), RecoveryAction.CONSUME, now);
                                if ("CONSUMED".equals(operation.getState())) {
                                    require(request.idempotencyKey().equals(operation.getConsumeKey()));
                                    return Mono.just(new Consumption(receipt(operation)));
                                }
                                return challenges.findAllByOperationId(operationId).collectList().flatMap(proofs -> {
                                    Instant evidenceExpiry = proofExpiry(proofs, now);
                                    if (!consumable(operation, now) || evidenceExpiry == null) {
                                        return audit(operation, "CONSUME_REJECTED", now).thenReturn(new Consumption(null));
                                    }
                                    operation.setState("CONSUMED");
                                    operation.setExecutionId(UUID.randomUUID());
                                    operation.setConsumeKey(request.idempotencyKey());
                                    operation.setReceiptExpiresAt(earliest(operation.getExpiresAt(),
                                            now.plus(properties.getReceiptTtl()), context.validUntil(), evidenceExpiry));
                                    operation.setExecutionOutcome(RecoveryExecutionOutcome.PENDING.name());
                                    return operations.save(operation).flatMap(saved -> audit(saved, "CONSUMED", now)
                                            .thenReturn(new Consumption(receipt(saved))));
                                });
                            }))))
                    .flatMap(result -> result.receipt() == null ? Mono.error(new RecoveryDeniedException())
                            : Mono.just(result.receipt()));
        });
    }

    @Override
    public Mono<Void> recordRecoveryExecution(UUID operationId, RecordRecoveryExecutionRequest request) {
        return Mono.defer(() -> {
            require(operationId != null && request != null && request.executionId() != null && request.outcome() != null);
            return admitted(request.binding(), RecoveryAction.RECORD_EXECUTION, context ->
                    external(() -> executor.verify(context, request.executionId(), request.outcome()))
                            .switchIfEmpty(Mono.error(new RecoveryUnavailableException()))
                            .flatMap(evidence -> transaction(() -> operations.databaseNow().flatMap(now ->
                                    load(operationId, context).flatMap(operation -> {
                                        checkContext(context, request.binding(), RecoveryAction.RECORD_EXECUTION, now);
                                        require("CONSUMED".equals(operation.getState())
                                                && request.executionId().equals(operation.getExecutionId())
                                                && evidence.binding().equals(context.binding())
                                                && evidence.executionId().equals(operation.getExecutionId())
                                                && evidence.outcome() == request.outcome());
                                        RecoveryExecutionOutcome current = RecoveryExecutionOutcome.valueOf(operation.getExecutionOutcome());
                                        if (current == evidence.outcome()) return Mono.<Void>empty();
                                        require(current == RecoveryExecutionOutcome.PENDING
                                                || (current == RecoveryExecutionOutcome.UNKNOWN
                                                && evidence.outcome() != RecoveryExecutionOutcome.PENDING));
                                        operation.setExecutionOutcome(evidence.outcome().name());
                                        return operations.save(operation)
                                                .then(audit(operation, "EXECUTION_" + evidence.outcome().name(), now));
                                    })))));
        });
    }

    private <T> Mono<T> admitted(RecoveryBinding binding, RecoveryAction action,
            Function<TrustedRecoveryContext, Mono<T>> work) {
        return admit(binding, action).flatMap(context -> Mono.defer(() -> work.apply(context))
                .onErrorResume(RecoveryDeniedException.class, ignored ->
                        rejected(context, action).then(Mono.error(new RecoveryDeniedException()))));
    }

    private Mono<Void> rejected(TrustedRecoveryContext context, RecoveryAction action) {
        return external(() -> rejectionAudit.recordRejection(context, action)).flatMap(receipt -> {
            if (receipt.auditId() == null || receipt.committedAt() == null) {
                return Mono.error(new RecoveryUnavailableException());
            }
            return Mono.<Void>empty();
        });
    }

    private Mono<TrustedRecoveryContext> admit(RecoveryBinding binding, RecoveryAction action) {
        require(properties.isEnabled() && binding != null && "PERMANENT_AUTHENTICATION_UNLOCK".equals(binding.purpose()));
        return external(() -> admission.admit(binding, action))
                .map(context -> {
                    require(binding.equals(context.binding()) && context.action() == action);
                    return context;
                });
    }

    private Mono<SCARecoveryOperation> load(UUID id, TrustedRecoveryContext context) {
        return operations.findById(id).filter(operation -> binding(operation).equals(context.binding())
                        && context.ownerLinkId().equals(operation.getOwnerLinkId()))
                .switchIfEmpty(Mono.error(new RecoveryDeniedException()));
    }

    private Mono<RecoveryOperationResponse> existingCreation(CreateRecoveryOperationRequest request, TrustedRecoveryContext context) {
        RecoveryBinding expected = request.binding();
        return operations.findByIssuerAndTenantAndClientIdAndActorSubjectAndCreateKey(expected.issuer(),
                        expected.tenant(), expected.clientId(), expected.actorSubject(), request.idempotencyKey())
                .map(operation -> {
                    require(binding(operation).equals(expected) && context.ownerLinkId().equals(operation.getOwnerLinkId()));
                    return response(operation);
                });
    }

    private void checkContext(TrustedRecoveryContext context, RecoveryBinding expected, RecoveryAction action, Instant now) {
        require(context != null && context.binding() != null && context.binding().equals(expected)
                && context.action() == action && nonblank(context.ownerLinkId())
                && nonblank(context.identityLinkAttestationId()) && nonblank(context.lockAttestationId())
                && context.validUntil() != null && now.isBefore(context.validUntil()));
    }

    private void checkOpen(SCARecoveryOperation operation, Instant now) {
        require("OPEN".equals(operation.getState()) && now.isBefore(operation.getExpiresAt())
                && operation.getAttemptCount() < operation.getMaxAttempts());
    }

    private void checkChallenge(SCARecoveryChallenge challenge, UUID operationId, Instant now) {
        require(operationId.equals(challenge.getOperationId()) && !Boolean.TRUE.equals(challenge.getVerified())
                && now.isBefore(challenge.getExpiresAt()) && challenge.getAttemptCount() < challenge.getMaxAttempts());
    }

    private void validateIssued(UUID operationId, UUID reservationId, IssueRecoveryChallengeRequest request,
            IssuedRecoveryChallenge issued, Instant now) {
        require(operationId.equals(issued.operationId()) && reservationId.equals(issued.reservationId())
                && request.binding().equals(issued.binding())
                && request.category() == issued.category() && nonblank(issued.providerId())
                && nonblank(issued.enrollmentId()) && nonblank(issued.independenceId())
                && nonblank(issued.providerChallengeId()) && issued.expiresAt() != null
                && now.isBefore(issued.expiresAt()));
    }

    private boolean proofMatches(VerifiedRecoveryProof proof, SCARecoveryChallenge challenge,
            RecoveryBinding binding, Instant now) {
        return proof != null && proof.accepted() && challenge.getOperationId().equals(proof.operationId())
                && challenge.getId().equals(proof.challengeId())
                && challenge.getVerificationReservationId().equals(proof.reservationId()) && binding.equals(proof.binding())
                && proof.category() != null && challenge.getCategory().equals(proof.category().name())
                && challenge.getProviderId().equals(proof.providerId())
                && challenge.getEnrollmentId().equals(proof.enrollmentId())
                && challenge.getIndependenceId().equals(proof.independenceId())
                && challenge.getProviderChallengeId().equals(proof.providerChallengeId())
                && nonblank(proof.attestationId()) && proof.expiresAt() != null && now.isBefore(proof.expiresAt());
    }

    private boolean consumable(SCARecoveryOperation operation, Instant now) {
        return "OPEN".equals(operation.getState()) && now.isBefore(operation.getExpiresAt())
                && operation.getAttemptCount() <= operation.getMaxAttempts();
    }

    private Instant proofExpiry(List<SCARecoveryChallenge> proofs, Instant now) {
        Instant latest = null;
        for (SCARecoveryChallenge possession : proofs) {
            if (!liveProof(possession, now) || !"POSSESSION".equals(possession.getCategory())) continue;
            for (SCARecoveryChallenge knowledge : proofs) {
                if (!liveProof(knowledge, now) || !"KNOWLEDGE".equals(knowledge.getCategory())
                        || !independent(possession, knowledge)) continue;
                Instant horizon = earliest(possession.getProofExpiresAt(), possession.getExpiresAt(),
                        knowledge.getProofExpiresAt(), knowledge.getExpiresAt());
                if (latest == null || horizon.isAfter(latest)) latest = horizon;
            }
        }
        return latest;
    }

    private boolean liveProof(SCARecoveryChallenge proof, Instant now) {
        return Boolean.TRUE.equals(proof.getVerified()) && nonblank(proof.getAttestationId())
                && proof.getProofExpiresAt() != null && now.isBefore(proof.getProofExpiresAt())
                && now.isBefore(proof.getExpiresAt());
    }

    private boolean independent(SCARecoveryChallenge first, SCARecoveryChallenge second) {
        return !first.getIndependenceId().equals(second.getIndependenceId())
                && !(first.getProviderId().equals(second.getProviderId())
                && first.getEnrollmentId().equals(second.getEnrollmentId()))
                && !(first.getProviderId().equals(second.getProviderId())
                && first.getAttestationId().equals(second.getAttestationId()));
    }

    private Mono<Void> audit(SCARecoveryOperation operation, String event, Instant now) {
        return audit(operation, event, now, null);
    }

    private Mono<Void> audit(SCARecoveryOperation operation, String event, Instant now, UUID reservationId) {
        SCARecoveryAudit audit = new SCARecoveryAudit();
        audit.setId(UUID.randomUUID());
        audit.setOperationId(operation.getId());
        audit.setReservationId(reservationId);
        audit.setEventType(event);
        audit.setOccurredAt(now);
        audit.setExecutionId(operation.getExecutionId());
        return audits.save(audit).then();
    }

    private <T> Mono<T> external(Supplier<Mono<T>> call) {
        // Do not propagate credential-bearing provider exceptions into receiver logs or error bodies.
        return Mono.defer(call).switchIfEmpty(Mono.error(new RecoveryUnavailableException()))
                .onErrorMap(error -> error instanceof RecoveryDeniedException
                        ? new RecoveryDeniedException() : new RecoveryUnavailableException());
    }

    private <T> Mono<T> transaction(Supplier<Mono<T>> work) {
        // Every retry reloads the versioned operation within a new transaction. No JVM lock is authority.
        return Mono.defer(() -> transactions.transactional(Mono.defer(work)))
                .retryWhen(Retry.max(8).filter(OptimisticLockingFailureException.class::isInstance)
                        .onRetryExhaustedThrow((spec, signal) -> new RecoveryDeniedException()));
    }

    private RecoveryChallengeReference reference(SCARecoveryChallenge challenge, RecoveryBinding binding) {
        return new RecoveryChallengeReference(challenge.getOperationId(), challenge.getId(), challenge.getVerificationReservationId(), binding,
                RecoveryFactorCategory.valueOf(challenge.getCategory()), challenge.getProviderId(),
                challenge.getEnrollmentId(), challenge.getIndependenceId(), challenge.getProviderChallengeId(),
                challenge.getExpiresAt());
    }

    private RecoveryOperationResponse response(SCARecoveryOperation operation) {
        return new RecoveryOperationResponse(operation.getId(), operation.getExpiresAt());
    }

    private RecoveryVerificationResponse acknowledged(UUID operationId) {
        return new RecoveryVerificationResponse(operationId, "ACKNOWLEDGED");
    }

    private RecoveryAuthorizationReceipt receipt(SCARecoveryOperation operation) {
        return new RecoveryAuthorizationReceipt(operation.getId(), operation.getExecutionId(), binding(operation),
                operation.getReceiptExpiresAt());
    }

    private RecoveryBinding binding(SCARecoveryOperation operation) {
        return new RecoveryBinding(operation.getIssuer(), operation.getTenant(), operation.getClientId(),
                operation.getDelegatingClientId(), operation.getActorSubject(), operation.getTargetIssuer(),
                operation.getTargetRealm(), operation.getTargetSubject(), operation.getPopulation(),
                operation.getPurpose(), operation.getLockGeneration());
    }

    private void setBinding(SCARecoveryOperation operation, RecoveryBinding binding) {
        operation.setIssuer(binding.issuer());
        operation.setTenant(binding.tenant());
        operation.setClientId(binding.clientId());
        operation.setDelegatingClientId(binding.delegatingClientId());
        operation.setActorSubject(binding.actorSubject());
        operation.setTargetIssuer(binding.targetIssuer());
        operation.setTargetRealm(binding.targetRealm());
        operation.setTargetSubject(binding.targetSubject());
        operation.setPopulation(binding.population());
        operation.setPurpose(binding.purpose());
        operation.setLockGeneration(binding.lockGeneration());
    }

    private static Instant earliest(Instant... values) {
        Instant earliest = values[0];
        for (Instant value : values) if (value.isBefore(earliest)) earliest = value;
        return earliest;
    }

    private static boolean nonblank(String value) { return value != null && !value.isBlank() && value.length() <= 512; }
    private static boolean validKey(String value) { return nonblank(value) && value.length() <= 128; }
    private static void require(boolean condition) { if (!condition) throw new RecoveryDeniedException(); }
    private record Consumption(RecoveryAuthorizationReceipt receipt) { }
}
