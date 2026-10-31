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

package io.fireflysoftware.core.sca.core.recovery;

import io.fireflysoftware.core.sca.core.recovery.impl.SCARecoveryServiceImpl;
import io.fireflysoftware.core.sca.interfaces.recovery.ConsumeRecoveryAuthorizationRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.CreateRecoveryOperationRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.IssueRecoveryChallengeRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.RecordRecoveryExecutionRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryAction;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryAuthorizationReceipt;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryBinding;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryExecutionOutcome;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryFactorCategory;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryProofKind;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryVerificationResponse;
import io.fireflysoftware.core.sca.interfaces.recovery.VerifyRecoveryChallengeRequest;
import io.fireflysoftware.core.sca.models.entities.SCARecoveryChallenge;
import io.fireflysoftware.core.sca.models.entities.SCARecoveryOperation;
import io.fireflysoftware.core.sca.models.repositories.SCARecoveryAuditRepository;
import io.fireflysoftware.core.sca.models.repositories.SCARecoveryChallengeRepository;
import io.fireflysoftware.core.sca.models.repositories.SCARecoveryOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit policy checks only. Real connection concurrency/rollback belongs to the repository IT suite. */
class SCARecoveryServiceTest {
    private final Instant now = Instant.parse("2026-10-31T12:00:00Z");
    private final UUID operationId = UUID.randomUUID();
    private final RecoveryBinding binding = new RecoveryBinding("issuer", "tenant", "client", null,
            "actor", "identity-issuer", "realm", "subject", "CUSTOMER", "PERMANENT_AUTHENTICATION_UNLOCK", "lock-7");
    private SCARecoveryOperationRepository operations;
    private SCARecoveryChallengeRepository challenges;
    private SCARecoveryAuditRepository audits;
    private RecoveryAdmissionPort admission;
    private RecoveryFactorProvider factors;
    private RecoveryExecutionVerifier executor;
    private TransactionalOperator transactions;
    private RecoveryProperties properties;
    private RecoveryRejectionAuditPort rejectionAudit;
    private SCARecoveryService service;
    private SCARecoveryOperation operation;
    private SCARecoveryChallenge challenge;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        operations = mock(SCARecoveryOperationRepository.class);
        challenges = mock(SCARecoveryChallengeRepository.class);
        audits = mock(SCARecoveryAuditRepository.class);
        admission = mock(RecoveryAdmissionPort.class);
        factors = mock(RecoveryFactorProvider.class);
        executor = mock(RecoveryExecutionVerifier.class);
        transactions = mock(TransactionalOperator.class);
        rejectionAudit = mock(RecoveryRejectionAuditPort.class);
        properties = new RecoveryProperties();
        properties.setEnabled(true);
        when(admission.isQualified()).thenReturn(true);
        when(factors.isQualified()).thenReturn(true);
        when(executor.isQualified()).thenReturn(true);
        when(rejectionAudit.isQualified()).thenReturn(true);
        when(rejectionAudit.recordRejection(any(), any())).thenReturn(Mono.just(
                new RecoveryRejectionAuditReceipt(UUID.randomUUID(), now)));
        when(admission.admit(any(), any())).thenAnswer(invocation -> Mono.just(context(invocation.getArgument(1))));
        when(transactions.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(operations.databaseNow()).thenAnswer(ignored -> Mono.just(now));
        operation = operation();
        challenge = challenge("POSSESSION", "device-a", "enrollment-a");
        when(operations.findById(operationId)).thenAnswer(ignored -> Mono.just(operation));
        when(operations.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(challenges.findById(challenge.getId())).thenAnswer(ignored -> Mono.just(challenge));
        when(challenges.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(challenges.existsByOperationIdAndVerificationReservationIdIsNotNull(operationId)).thenReturn(Mono.just(false));
        when(challenges.findAllByOperationId(operationId)).thenReturn(Flux.empty());
        when(audits.existsByOperationIdAndEventTypeAndReservationId(any(), anyString(), any())).thenReturn(Mono.just(true));
        when(audits.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(factors.verify(any(), any(), any(), anyString())).thenAnswer(ignored -> Mono.just(proof(false)));
        service = new SCARecoveryServiceImpl(operations, challenges, audits, transactions,
                admission, factors, executor, properties, rejectionAudit);
        clearInvocations(operations, challenges, audits, admission, factors, executor, transactions, rejectionAudit);
    }

    @Test
    void unavailableCapabilitiesCannotEnableRecovery() {
        when(factors.isQualified()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> new SCARecoveryServiceImpl(operations, challenges,
                audits, transactions, admission, factors, executor, properties, rejectionAudit));
    }

    @Test
    void allFourCapabilitiesRequireExplicitQualification() {
        when(admission.isQualified()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> new SCARecoveryServiceImpl(operations, challenges,
                audits, transactions, admission, factors, executor, properties, rejectionAudit));
        when(admission.isQualified()).thenReturn(true);
        when(executor.isQualified()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> new SCARecoveryServiceImpl(operations, challenges,
                audits, transactions, admission, factors, executor, properties, rejectionAudit));
        when(executor.isQualified()).thenReturn(true);
        when(rejectionAudit.isQualified()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> new SCARecoveryServiceImpl(operations, challenges,
                audits, transactions, admission, factors, executor, properties, rejectionAudit));
    }

    @Test
    void boundaryAuditFailureAndEmptyAcknowledgementCannotAcknowledgeDeniedProof() {
        UUID unknown = UUID.randomUUID();
        when(challenges.findById(unknown)).thenReturn(Mono.empty());
        VerifyRecoveryChallengeRequest request = new VerifyRecoveryChallengeRequest(binding, unknown,
                RecoveryProofKind.POSSESSION_RESPONSE, "code");
        when(rejectionAudit.recordRejection(any(), any())).thenReturn(Mono.empty());
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request))
                .expectError(RecoveryUnavailableException.class).verify();
        when(rejectionAudit.recordRejection(any(), any())).thenReturn(Mono.error(new IllegalStateException("outage")));
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request))
                .expectError(RecoveryUnavailableException.class).verify();
        verify(factors, never()).verify(any(), any(), any(), anyString());
    }

    @Test
    void providerDenialKeepsExactReservationAndAppendsPrivateRejection() {
        when(factors.verify(any(), any(), any(), anyString())).thenReturn(Mono.error(new RecoveryDeniedException()));
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request())).expectNextCount(1).verifyComplete();
        assertEquals(1, operation.getAttemptCount());
        assertNotNull(challenge.getVerificationReservationId());
        verify(audits).save(argThat(audit -> "PROOF_REJECTED".equals(audit.getEventType())
                && challenge.getVerificationReservationId().equals(audit.getReservationId())));
        verifyNoInteractions(rejectionAudit);
    }

    @Test
    void disabledAndWrongPurposeDoNotReadPersistence() {
        properties.setEnabled(false);
        StepVerifier.create(service.createRecoveryOperation(new CreateRecoveryOperationRequest(binding, "key")))
                .expectError(RecoveryDeniedException.class).verify();
        verifyNoInteractions(operations, factors);
    }

    @Test
    void untrustedBodyBindingCannotReplaceDerivedContext() {
        RecoveryBinding swapped = new RecoveryBinding("issuer", "other-tenant", "client", null,
                "actor", "identity-issuer", "realm", "subject", "CUSTOMER", binding.purpose(), "lock-7");
        StepVerifier.create(service.consumeRecoveryAuthorization(operationId,
                        new ConsumeRecoveryAuthorizationRequest(swapped, "consume")))
                .expectError(RecoveryDeniedException.class).verify();
        verifyNoInteractions(operations, factors);
    }

    @Test
    void rejectedProofIsPrivateAndPersistsWholeOperationBudget() {
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request()))
                .assertNext(response -> assertEquals(new RecoveryVerificationResponse(operationId, "ACKNOWLEDGED"), response))
                .verifyComplete();
        assertEquals(1, operation.getAttemptCount());
        assertEquals(1, challenge.getAttemptCount());
        assertFalse(challenge.getVerified());
        verify(audits).save(argThat(audit -> "PROOF_REJECTED".equals(audit.getEventType())));
    }

    @Test
    void acceptedAndRejectedProofsHaveIdenticalPublicAcknowledgement() {
        when(factors.verify(any(), any(), any(), anyString())).thenAnswer(ignored -> Mono.just(proof(true)));
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request()))
                .expectNext(new RecoveryVerificationResponse(operationId, "ACKNOWLEDGED")).verifyComplete();
        assertTrue(challenge.getVerified());
        assertEquals("attestation", challenge.getAttestationId());
        assertEquals(1, operation.getAttemptCount());
    }

    @Test
    void sameAttestationWithWrongImmutableBindingIsNotAccepted() {
        VerifiedRecoveryProof forged = new VerifiedRecoveryProof(operationId, challenge.getId(), challenge.getVerificationReservationId(), binding,
                RecoveryFactorCategory.POSSESSION, "other-provider", challenge.getEnrollmentId(),
                challenge.getIndependenceId(), challenge.getProviderChallengeId(), true, "attestation", now.plusSeconds(60));
        when(factors.verify(any(), any(), any(), anyString())).thenReturn(Mono.just(forged));
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request()))
                .expectNext(new RecoveryVerificationResponse(operationId, "ACKNOWLEDGED")).verifyComplete();
        assertFalse(challenge.getVerified());
        assertEquals(1, operation.getAttemptCount());
    }

    @Test
    void knowledgeNeverUsesRawPossessionResponse() {
        challenge.setCategory("KNOWLEDGE");
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request()))
                .expectNext(new RecoveryVerificationResponse(operationId, "ACKNOWLEDGED")).verifyComplete();
        verify(factors, never()).verify(any(), any(), any(), anyString());
        assertEquals(0, operation.getAttemptCount());
    }

    @Test
    void reissuedChallengeCannotResetAnExhaustedWholeOperationBudget() {
        operation.setAttemptCount(operation.getMaxAttempts() - 1);
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request()))
                .expectNextCount(1).verifyComplete();
        assertEquals(operation.getMaxAttempts(), operation.getAttemptCount());
        assertEquals("EXHAUSTED", operation.getState());
        StepVerifier.create(service.issueRecoveryChallenge(operationId,
                        new IssueRecoveryChallengeRequest(binding, RecoveryFactorCategory.POSSESSION)))
                .expectError(RecoveryDeniedException.class).verify();
        verify(factors, never()).issue(any(), any(), any(), any());
    }

    @Test
    void expiredOrAlreadyVerifiedChallengeHasSameAcknowledgementWithoutProviderCall() {
        challenge.setExpiresAt(now);
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request())).expectNextCount(1).verifyComplete();
        challenge.setExpiresAt(now.plusSeconds(60));
        challenge.setVerified(true);
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request())).expectNextCount(1).verifyComplete();
        verify(factors, never()).verify(any(), any(), any(), anyString());
    }

    @Test
    void consumeRequiresDifferentCategoriesAndIndependentEnrollmentProvenance() {
        SCARecoveryChallenge possession = verified("POSSESSION", "device", "enrollment");
        SCARecoveryChallenge sameDevice = verified("KNOWLEDGE", "device", "another-enrollment");
        when(challenges.findAllByOperationId(operationId)).thenReturn(Flux.just(possession, sameDevice));
        assertConsumeDenied();
        sameDevice.setIndependenceId("different-device");
        sameDevice.setEnrollmentId("enrollment");
        assertConsumeDenied();
        sameDevice.setEnrollmentId("different-enrollment");
        sameDevice.setCategory("POSSESSION");
        assertConsumeDenied();
        verify(operations, never()).save(any());
    }

    @Test
    void expiryIsExclusiveForOperationsAndProofs() {
        SCARecoveryChallenge possession = verified("POSSESSION", "device-a", "enrollment-a");
        SCARecoveryChallenge knowledge = verified("KNOWLEDGE", "device-b", "enrollment-b");
        when(challenges.findAllByOperationId(operationId)).thenReturn(Flux.just(possession, knowledge));
        operation.setExpiresAt(now);
        assertConsumeDenied();
        operation.setExpiresAt(now.plusSeconds(120));
        knowledge.setProofExpiresAt(now);
        assertConsumeDenied();
    }

    @Test
    void consumptionAndUnknownReconciliationPreserveSameExecutionAndReceipt() {
        when(challenges.findAllByOperationId(operationId)).thenReturn(Flux.just(
                verified("POSSESSION", "device-a", "enrollment-a"), verified("KNOWLEDGE", "device-b", "enrollment-b")));
        RecoveryAuthorizationReceipt receipt = service.consumeRecoveryAuthorization(operationId,
                new ConsumeRecoveryAuthorizationRequest(binding, "consume-key")).block();
        assertNotNull(receipt);
        assertEquals("CONSUMED", operation.getState());
        operation.setExecutionOutcome("UNKNOWN");
        StepVerifier.create(service.consumeRecoveryAuthorization(operationId,
                        new ConsumeRecoveryAuthorizationRequest(binding, "consume-key")))
                .expectNext(receipt).verifyComplete();
        StepVerifier.create(service.consumeRecoveryAuthorization(operationId,
                        new ConsumeRecoveryAuthorizationRequest(binding, "changed-key")))
                .expectError(RecoveryDeniedException.class).verify();
        verify(operations, times(1)).save(any());
        assertEquals(receipt.executionId(), operation.getExecutionId());
        assertEquals("UNKNOWN", operation.getExecutionOutcome());
    }

    @Test
    void finalAcceptedAttemptCanConsumeButCannotVerifyAgain() {
        operation.setAttemptCount(operation.getMaxAttempts());
        when(challenges.findAllByOperationId(operationId)).thenReturn(Flux.just(
                verified("POSSESSION", "device-a", "enrollment-a"), verified("KNOWLEDGE", "device-b", "enrollment-b")));
        StepVerifier.create(service.consumeRecoveryAuthorization(operationId,
                        new ConsumeRecoveryAuthorizationRequest(binding, "key")))
                .expectNextCount(1).verifyComplete();
    }

    @Test
    void executionJournalIsMonotonicAndNeverReopensProof() {
        UUID executionId = UUID.randomUUID();
        operation.setState("CONSUMED");
        operation.setExecutionId(executionId);
        operation.setExecutionOutcome("UNKNOWN");
        when(executor.verify(any(), eq(executionId), any())).thenAnswer(invocation -> Mono.just(
                new VerifiedRecoveryExecution(binding, executionId, invocation.getArgument(2))));
        StepVerifier.create(service.recordRecoveryExecution(operationId,
                        new RecordRecoveryExecutionRequest(binding, executionId, RecoveryExecutionOutcome.SUCCEEDED)))
                .verifyComplete();
        StepVerifier.create(service.recordRecoveryExecution(operationId,
                        new RecordRecoveryExecutionRequest(binding, executionId, RecoveryExecutionOutcome.FAILED)))
                .expectError(RecoveryDeniedException.class).verify();
        assertEquals("SUCCEEDED", operation.getExecutionOutcome());
        assertEquals("CONSUMED", operation.getState());
        assertEquals(executionId, operation.getExecutionId());
    }

    @Test
    void earlierRejectedReservationCompletingLastCannotInvalidateAnAcceptedIndependentPair() {
        operation.setAttemptCount(operation.getMaxAttempts() - 2);
        SCARecoveryChallenge knowledge = verified("KNOWLEDGE", "knowledge-origin", "knowledge-enrollment");
        SCARecoveryChallenge validPossession = challenge("POSSESSION", "valid-device", "valid-enrollment");
        when(challenges.findById(validPossession.getId())).thenReturn(Mono.just(validPossession));
        when(challenges.findAllByOperationId(operationId)).thenReturn(Flux.just(knowledge, challenge, validPossession));
        when(challenges.existsByOperationIdAndVerificationReservationIdIsNotNull(operationId)).thenAnswer(ignored ->
                Mono.just(challenge.getVerificationReservationId() != null
                        || validPossession.getVerificationReservationId() != null));
        Sinks.One<VerifiedRecoveryProof> rejectedProvider = Sinks.one();
        Sinks.One<VerifiedRecoveryProof> validProvider = Sinks.one();
        AtomicReference<RecoveryChallengeReference> earlier = new AtomicReference<>();
        AtomicReference<RecoveryChallengeReference> later = new AtomicReference<>();
        when(factors.verify(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            RecoveryChallengeReference reference = invocation.getArgument(1);
            if (reference.challengeId().equals(challenge.getId())) {
                earlier.set(reference);
                return rejectedProvider.asMono();
            }
            later.set(reference);
            return validProvider.asMono();
        });
        List<RecoveryVerificationResponse> acknowledgements = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        service.verifyRecoveryChallenge(operationId, request()).subscribe(acknowledgements::add, failures::add);
        service.verifyRecoveryChallenge(operationId, new VerifyRecoveryChallengeRequest(binding,
                validPossession.getId(), RecoveryProofKind.POSSESSION_RESPONSE, "code"))
                .subscribe(acknowledgements::add, failures::add);
        assertEquals(operation.getMaxAttempts(), operation.getAttemptCount());
        RecoveryChallengeReference reference = later.get();
        assertEquals(Sinks.EmitResult.OK, validProvider.tryEmitValue(new VerifiedRecoveryProof(operationId,
                reference.challengeId(), reference.reservationId(), binding, reference.category(), reference.providerId(),
                reference.enrollmentId(), reference.independenceId(), reference.providerChallengeId(),
                true, "valid-attestation", now.plusSeconds(60))));
        reference = earlier.get();
        assertEquals(Sinks.EmitResult.OK, rejectedProvider.tryEmitValue(new VerifiedRecoveryProof(operationId,
                reference.challengeId(), reference.reservationId(), binding, reference.category(), reference.providerId(),
                reference.enrollmentId(), reference.independenceId(), reference.providerChallengeId(),
                false, null, now.plusSeconds(60))));
        assertTrue(failures.isEmpty());
        assertEquals(2, acknowledgements.size());
        assertEquals("OPEN", operation.getState());
        StepVerifier.create(service.consumeRecoveryAuthorization(operationId,
                        new ConsumeRecoveryAuthorizationRequest(binding, "completion-order")))
                .expectNextCount(1).verifyComplete();
        assertEquals("CONSUMED", operation.getState());
    }

    @Test
    void optimisticReservationRetryDoesNotInvokeProviderUntilAReservationCommits() {
        java.util.concurrent.atomic.AtomicInteger saves = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (saves.getAndIncrement() == 0) {
                UUID challengeId = challenge.getId();
                operation = operation();
                challenge = challenge("POSSESSION", "device-a", "enrollment-a");
                challenge.setId(challengeId);
                return Mono.error(new OptimisticLockingFailureException("conflict"));
            }
            return Mono.just(invocation.getArgument(0));
        }).when(operations).save(any());
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request())).expectNextCount(1).verifyComplete();
        assertEquals(1, operation.getAttemptCount());
        verify(factors, times(1)).verify(any(), any(), any(), anyString());
    }

    @Test
    void inFlightLastReservationPreventsAnotherProviderGuess() {
        operation.setAttemptCount(operation.getMaxAttempts() - 1);
        when(factors.verify(any(), any(), any(), anyString())).thenReturn(Mono.never());
        reactor.core.Disposable pending = service.verifyRecoveryChallenge(operationId, request()).subscribe();
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request())).expectNextCount(1).verifyComplete();
        assertEquals(operation.getMaxAttempts(), operation.getAttemptCount());
        assertNotNull(challenge.getVerificationReservationId());
        verify(factors, times(1)).verify(any(), any(), any(), anyString());
        pending.dispose();
        assertEquals(operation.getMaxAttempts(), operation.getAttemptCount());
        assertNotNull(challenge.getVerificationReservationId());
    }

    @Test
    void inFlightLastIssuanceReservationPreventsAnotherDelivery() {
        operation.setChallengeCount(operation.getMaxChallenges() - 1);
        when(factors.issue(any(), any(), any(), any())).thenReturn(Mono.never());
        IssueRecoveryChallengeRequest request = new IssueRecoveryChallengeRequest(binding, RecoveryFactorCategory.POSSESSION);
        reactor.core.Disposable pending = service.issueRecoveryChallenge(operationId, request).subscribe();
        StepVerifier.create(service.issueRecoveryChallenge(operationId, request))
                .expectError(RecoveryDeniedException.class).verify();
        assertEquals(operation.getMaxChallenges(), operation.getChallengeCount());
        verify(factors, times(1)).issue(any(), any(), any(), any());
        pending.dispose();
        assertEquals(operation.getMaxChallenges(), operation.getChallengeCount());
    }

    @Test
    void providerFailureRetainsSpentBudgetAndPreventsRepeatInvocation() {
        when(factors.verify(any(), any(), any(), anyString())).thenReturn(Mono.error(new IllegalStateException("outage")));
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request()))
                .expectError(RecoveryUnavailableException.class).verify();
        assertEquals(1, operation.getAttemptCount());
        assertEquals(1, challenge.getAttemptCount());
        assertNotNull(challenge.getVerificationReservationId());
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request())).expectNextCount(1).verifyComplete();
        verify(factors, times(1)).verify(any(), any(), any(), anyString());
    }

    @Test
    void validFinalReservedSlotCanCompleteWithoutDoubleCounting() {
        operation.setAttemptCount(operation.getMaxAttempts() - 1);
        when(factors.verify(any(), any(), any(), anyString())).thenAnswer(ignored -> Mono.just(proof(true)));
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request())).expectNextCount(1).verifyComplete();
        assertEquals(operation.getMaxAttempts(), operation.getAttemptCount());
        assertEquals(1, challenge.getAttemptCount());
        assertTrue(challenge.getVerified());
        assertNull(challenge.getVerificationReservationId());
        assertEquals("OPEN", operation.getState());
    }

    @Test
    void auditFailureCannotBeReportedAsAcceptedTransition() {
        doReturn(Mono.error(new IllegalStateException("audit unavailable"))).when(audits).save(any());
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request()))
                .expectError(IllegalStateException.class).verify();
    }

    @Test
    void ownerLinkOrNativeGenerationChangeCannotConsumeOldProof() {
        when(admission.admit(any(), eq(RecoveryAction.CONSUME))).thenReturn(Mono.just(new TrustedRecoveryContext(
                binding, "new-owner-link", "current-identity", "current-lock", now.plusSeconds(60), RecoveryAction.CONSUME)));
        assertConsumeDenied();
        verify(challenges, never()).findAllByOperationId(any());
        RecoveryBinding nextGeneration = new RecoveryBinding(binding.issuer(), binding.tenant(), binding.clientId(),
                null, binding.actorSubject(), binding.targetIssuer(), binding.targetRealm(), binding.targetSubject(),
                binding.population(), binding.purpose(), "lock-8");
        when(admission.admit(any(), eq(RecoveryAction.CONSUME))).thenReturn(Mono.just(new TrustedRecoveryContext(
                nextGeneration, "link", "current-identity", "current-lock", now.plusSeconds(60), RecoveryAction.CONSUME)));
        assertConsumeDenied();
        verify(operations, never()).save(any());
    }

    @Test
    void admissionExpiryDuringExternalVerificationCannotPersistProof() {
        when(factors.verify(any(), any(), any(), anyString())).thenAnswer(ignored -> Mono.just(proof(true)));
        when(operations.databaseNow()).thenReturn(Mono.just(now), Mono.just(now.plusSeconds(300)));
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request()))
                .expectNext(new RecoveryVerificationResponse(operationId, "ACKNOWLEDGED")).verifyComplete();
        assertFalse(challenge.getVerified());
        assertEquals(1, operation.getAttemptCount());
        assertNotNull(challenge.getVerificationReservationId());
    }

    @Test
    void receiptExpiryNeverOutlivesEitherAcceptedFactor() {
        SCARecoveryChallenge possession = verified("POSSESSION", "device-a", "enrollment-a");
        SCARecoveryChallenge knowledge = verified("KNOWLEDGE", "device-b", "enrollment-b");
        knowledge.setProofExpiresAt(now.plusSeconds(5));
        when(challenges.findAllByOperationId(operationId)).thenReturn(Flux.just(possession, knowledge));
        StepVerifier.create(service.consumeRecoveryAuthorization(operationId,
                        new ConsumeRecoveryAuthorizationRequest(binding, "key")))
                .assertNext(receipt -> assertEquals(now.plusSeconds(5), receipt.expiresAt())).verifyComplete();
    }

    @Test
    void providerFailureCannotPropagateSubmittedSecret() {
        when(factors.verify(any(), any(), any(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("sensitive-code-attestation")));
        StepVerifier.create(service.verifyRecoveryChallenge(operationId, request()))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(RecoveryUnavailableException.class, error);
                    assertFalse(error.toString().contains("sensitive-code-attestation"));
                    assertNull(error.getCause());
                }).verify();
        assertEquals(1, operation.getAttemptCount());
        assertNotNull(challenge.getVerificationReservationId());
    }

    @Test
    void secretAndProofRecordsNeverRenderValues() {
        String secret = "sensitive-code-attestation";
        VerifyRecoveryChallengeRequest request = new VerifyRecoveryChallengeRequest(binding, challenge.getId(),
                RecoveryProofKind.PROVIDER_ATTESTATION, secret);
        assertFalse(request.toString().contains(secret));
        assertFalse(binding.toString().contains("subject"));
        assertEquals("VerifiedRecoveryProof[REDACTED]", proof(true).toString());
        assertEquals("RecoveryAuthorizationReceipt[REDACTED]",
                new RecoveryAuthorizationReceipt(operationId, UUID.randomUUID(), binding, now).toString());
    }

    private void assertConsumeDenied() {
        StepVerifier.create(service.consumeRecoveryAuthorization(operationId,
                        new ConsumeRecoveryAuthorizationRequest(binding, "key")))
                .expectError(RecoveryDeniedException.class).verify();
    }

    private VerifyRecoveryChallengeRequest request() {
        return new VerifyRecoveryChallengeRequest(binding, challenge.getId(), RecoveryProofKind.POSSESSION_RESPONSE, "code");
    }

    private TrustedRecoveryContext context(RecoveryAction action) {
        return new TrustedRecoveryContext(binding, "link", "identity-link-attestation", "lock-attestation",
                now.plusSeconds(300), action);
    }

    private VerifiedRecoveryProof proof(boolean accepted) {
        return new VerifiedRecoveryProof(operationId, challenge.getId(), challenge.getVerificationReservationId(), binding,
                RecoveryFactorCategory.valueOf(challenge.getCategory()), challenge.getProviderId(), challenge.getEnrollmentId(),
                challenge.getIndependenceId(), challenge.getProviderChallengeId(), accepted, "attestation", now.plusSeconds(60));
    }

    private SCARecoveryChallenge verified(String category, String independence, String enrollment) {
        SCARecoveryChallenge proof = challenge(category, independence, enrollment);
        proof.setVerified(true);
        proof.setAttestationId(UUID.randomUUID().toString());
        proof.setProofExpiresAt(now.plusSeconds(120));
        return proof;
    }

    private SCARecoveryChallenge challenge(String category, String independence, String enrollment) {
        SCARecoveryChallenge value = new SCARecoveryChallenge();
        value.setId(UUID.randomUUID());
        value.setVersion(0L);
        value.setOperationId(operationId);
        value.setCategory(category);
        value.setProviderId("provider");
        value.setEnrollmentId(enrollment);
        value.setIndependenceId(independence);
        value.setProviderChallengeId(UUID.randomUUID().toString());
        value.setCreatedAt(now.minusSeconds(5));
        value.setExpiresAt(now.plusSeconds(120));
        value.setAttemptCount(0);
        value.setMaxAttempts(3);
        value.setVerified(false);
        return value;
    }

    private SCARecoveryOperation operation() {
        SCARecoveryOperation value = new SCARecoveryOperation();
        value.setId(operationId);
        value.setVersion(0L);
        value.setIssuer(binding.issuer());
        value.setTenant(binding.tenant());
        value.setClientId(binding.clientId());
        value.setActorSubject(binding.actorSubject());
        value.setTargetIssuer(binding.targetIssuer());
        value.setTargetRealm(binding.targetRealm());
        value.setTargetSubject(binding.targetSubject());
        value.setPopulation(binding.population());
        value.setPurpose(binding.purpose());
        value.setLockGeneration(binding.lockGeneration());
        value.setOwnerLinkId("link");
        value.setIdentityLinkAttestationId("identity-link-attestation");
        value.setLockAttestationId("lock-attestation");
        value.setCreateKey("create-key");
        value.setState("OPEN");
        value.setCreatedAt(now.minusSeconds(5));
        value.setExpiresAt(now.plusSeconds(300));
        value.setAttemptCount(0);
        value.setMaxAttempts(5);
        value.setChallengeCount(1);
        value.setMaxChallenges(4);
        return value;
    }
}
