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
import io.fireflysoftware.core.sca.interfaces.recovery.*;
import io.fireflysoftware.core.sca.models.repositories.*;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real owning transactions and mapped repositories; native ports are explicit synthetic fixtures. */
class SCARecoveryNativeContractIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private SCARecoveryService service;
    private SCARecoveryOperationRepository operations;
    private SCARecoveryChallengeRepository challenges;
    private DatabaseRecoveryAuditSink sink;
    private RecoveryBinding binding;
    private final AtomicInteger factorReads = new AtomicInteger();

    @BeforeEach
    void fixture() {
        var connectionFactory = ConnectionFactories.get(ConnectionFactoryOptions.parse(required("SCA_RECOVERY_TEST_R2DBC_URL"))
                .mutate().option(ConnectionFactoryOptions.USER, required("SCA_RECOVERY_TEST_USER"))
                .option(ConnectionFactoryOptions.PASSWORD, required("SCA_RECOVERY_TEST_PASSWORD")).build());
        var factory = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(connectionFactory));
        operations = factory.getRepository(SCARecoveryOperationRepository.class);
        challenges = factory.getRepository(SCARecoveryChallengeRepository.class);
        var audits = factory.getRepository(SCARecoveryAuditRepository.class);
        var transactions = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
        sink = new DatabaseRecoveryAuditSink(factory.getRepository(SCARecoveryBoundaryAuditRepository.class), operations, transactions);
        binding = new RecoveryBinding("fixture-issuer", "fixture-tenant", "client-" + UUID.randomUUID(), null,
                "fixture-actor", "fixture-target-issuer", "fixture-realm", "subject-" + UUID.randomUUID(),
                "HUMAN", "PERMANENT_AUTHENTICATION_UNLOCK", "fixture-generation");
        RecoveryAdmissionPort admission = new RecoveryAdmissionPort() {
            @Override public boolean isQualified() { return true; }
            @Override public Mono<TrustedRecoveryContext> admit(RecoveryBinding expected, RecoveryAction action) {
                return operations.databaseNow().map(now -> new TrustedRecoveryContext(binding, "fixture-owner-link",
                        "fixture-identity-attestation", "fixture-lock-attestation", now.plusSeconds(3600), action));
            }
        };
        RecoveryFactorProvider factors = new RecoveryFactorProvider() {
            @Override public boolean isQualified() { return true; }
            @Override public Mono<IssuedRecoveryChallenge> issue(TrustedRecoveryContext context, UUID operationId,
                    UUID reservationId, RecoveryFactorCategory category) {
                return operations.databaseNow().map(now -> new IssuedRecoveryChallenge(operationId, reservationId,
                        context.binding(), category, "fixture-provider", "enrollment-" + category,
                        "independent-origin-" + category, "ceremony-" + UUID.randomUUID(), now.plusSeconds(180), "masked"));
            }
            @Override public Mono<VerifiedRecoveryProof> verify(TrustedRecoveryContext context, RecoveryChallengeReference reference,
                    RecoveryProofKind kind, String response) {
                assertEquals(RecoveryProofKind.PROVIDER_ATTESTATION, kind);
                factorReads.incrementAndGet();
                return operations.databaseNow().map(now -> new VerifiedRecoveryProof(reference.operationId(), reference.challengeId(),
                        reference.reservationId(), context.binding(), reference.category(), reference.providerId(),
                        reference.enrollmentId(), reference.independenceId(), reference.providerChallengeId(),
                        true, "attestation-" + reference.reservationId(), now.plusSeconds(120)));
            }
        };
        RecoveryExecutionVerifier executor = new RecoveryExecutionVerifier() {
            @Override public boolean isQualified() { return true; }
            @Override public Mono<VerifiedRecoveryExecution> verify(TrustedRecoveryContext context, UUID executionId,
                    RecoveryExecutionOutcome outcome) { return Mono.error(new RecoveryUnavailableException()); }
        };
        var properties = new RecoveryProperties();
        properties.setEnabled(true);
        service = new SCARecoveryServiceImpl(operations, challenges, audits, transactions, admission, factors,
                executor, properties, new DatabaseRecoveryRejectionAuditAdapter(sink));
    }

    @Test
    void reservationResponseFollowsCommitAndConcurrentRetryReturnsSameDurableIdentity() {
        UUID operation = create();
        UUID challenge = issue(operation, RecoveryFactorCategory.POSSESSION);
        var request = new ReserveRecoveryVerificationRequest(binding, challenge);
        var first = service.reserveRecoveryVerification(operation, request).doOnNext(reservation -> {
            assertEquals(1, scalar("SELECT count(*) FROM public.sca_recovery_audit WHERE event_type = 'PROOF_RESERVED' AND reservation_id = ?",
                    reservation.reservationId()));
            assertEquals(1, scalar("SELECT attempt_count FROM public.sca_recovery_operation WHERE id = ?", operation));
        });
        var pair = Mono.zip(first, service.reserveRecoveryVerification(operation, request)).block(TIMEOUT);
        assertNotNull(pair);
        assertEquals(pair.getT1(), pair.getT2());
        assertEquals(1, scalar("SELECT attempt_count FROM public.sca_recovery_operation WHERE id = ?", operation));
        assertEquals(1, scalar("SELECT attempt_count FROM public.sca_recovery_challenge WHERE id = ?", challenge));
        assertEquals(0, factorReads.get());
    }

    @Test
    void completionAndCompletedRetryNeverRecountOrVerifyFactorTwice() {
        UUID operation = create();
        UUID challenge = issue(operation, RecoveryFactorCategory.KNOWLEDGE);
        var reservation = service.reserveRecoveryVerification(operation, new ReserveRecoveryVerificationRequest(binding, challenge)).block(TIMEOUT);
        var request = new CompleteRecoveryVerificationRequest(binding, challenge, reservation.reservationId(), "opaque-native-reference");
        var first = service.completeRecoveryVerification(operation, request).block(TIMEOUT);
        assertEquals(first, service.completeRecoveryVerification(operation, request).block(TIMEOUT));
        assertEquals(1, factorReads.get());
        assertEquals(1, scalar("SELECT attempt_count FROM public.sca_recovery_operation WHERE id = ?", operation));
        assertTrue(challenges.findById(challenge).block(TIMEOUT).getVerified());
    }

    @Test
    void foreignReservationCannotCompleteOrSpendAnAdditionalAttempt() {
        UUID operation = create();
        UUID challenge = issue(operation, RecoveryFactorCategory.POSSESSION);
        var reserved = service.reserveRecoveryVerification(operation, new ReserveRecoveryVerificationRequest(binding, challenge)).block(TIMEOUT);
        assertEquals(new RecoveryVerificationResponse(operation, "ACKNOWLEDGED"), service.completeRecoveryVerification(operation,
                new CompleteRecoveryVerificationRequest(binding, challenge, UUID.randomUUID(), "opaque-reference")).block(TIMEOUT));
        assertEquals(0, factorReads.get());
        var persisted = challenges.findById(challenge).block(TIMEOUT);
        assertFalse(persisted.getVerified());
        assertEquals(reserved.reservationId(), persisted.getVerificationReservationId());
        assertEquals(1, persisted.getAttemptCount());
    }

    @Test
    void onlineReceiptVerificationUsesStoredConsumptionAndNeverAuthorizesUnknown() {
        UUID operation = create();
        complete(operation, RecoveryFactorCategory.KNOWLEDGE);
        complete(operation, RecoveryFactorCategory.POSSESSION);
        var receipt = service.consumeRecoveryAuthorization(operation, new ConsumeRecoveryAuthorizationRequest(binding, "consume-fixture")).block(TIMEOUT);
        var request = new VerifyRecoveryAuthorizationRequest(binding, receipt.executionId(), receipt.expiresAt());
        assertEquals(receipt, service.verifyRecoveryAuthorization(operation, request).block(TIMEOUT));
        assertThrows(RecoveryDeniedException.class, () -> service.verifyRecoveryAuthorization(operation,
                new VerifyRecoveryAuthorizationRequest(binding, receipt.executionId(), receipt.expiresAt().plusSeconds(1))).block(TIMEOUT));
        var consumed = operations.findById(operation).block(TIMEOUT);
        consumed.setExecutionOutcome("UNKNOWN");
        operations.save(consumed).block(TIMEOUT);
        assertThrows(RecoveryDeniedException.class, () -> service.verifyRecoveryAuthorization(operation, request).block(TIMEOUT));
        assertEquals(receipt.executionId(), operations.findById(operation).block(TIMEOUT).getExecutionId());
    }

    @Test
    void boundarySinkAcknowledgesOnlyCommittedRowsAndStoresAnonymousRefusalWithoutOperation() {
        var receipt = sink.record(null, null, "REFUSED").doOnNext(committed -> {
            assertEquals(1, scalar("SELECT count(*) FROM public.sca_recovery_boundary_audit WHERE id = ? AND issuer IS NULL AND action IS NULL",
                    committed.auditId()));
        }).block(TIMEOUT);
        assertNotNull(receipt);
        assertNotNull(receipt.committedAt());
        assertThrows(RuntimeException.class, () -> sink.record(new RecoveryAuditActor("", "tenant", "actor", "client", "HUMAN"),
                RecoveryAction.CREATE, "ADMITTED").block(TIMEOUT));
    }

    @Test
    void unknownOperationDenialCommitsSanitizedCoreBoundaryAudit() {
        assertThrows(RecoveryDeniedException.class, () -> service.consumeRecoveryAuthorization(UUID.randomUUID(),
                new ConsumeRecoveryAuthorizationRequest(binding, "unknown-fixture")).block(TIMEOUT));
        assertEquals(1, scalar("SELECT count(*) FROM public.sca_recovery_boundary_audit WHERE client_id = ? AND outcome = 'REJECTED'",
                binding.clientId()));
    }

    private UUID create() { return service.createRecoveryOperation(new CreateRecoveryOperationRequest(binding, "fixture-create")).block(TIMEOUT).operationId(); }
    private UUID issue(UUID operation, RecoveryFactorCategory category) {
        return service.issueRecoveryChallenge(operation, new IssueRecoveryChallengeRequest(binding, category)).block(TIMEOUT).challengeId();
    }
    private void complete(UUID operation, RecoveryFactorCategory category) {
        UUID challenge = issue(operation, category);
        var reservation = service.reserveRecoveryVerification(operation, new ReserveRecoveryVerificationRequest(binding, challenge)).block(TIMEOUT);
        service.completeRecoveryVerification(operation, new CompleteRecoveryVerificationRequest(binding, challenge,
                reservation.reservationId(), "opaque-native-reference")).block(TIMEOUT);
    }
    private static int scalar(String sql, Object parameter) {
        try (Connection connection = DriverManager.getConnection(required("SCA_RECOVERY_TEST_JDBC_URL"),
                required("SCA_RECOVERY_TEST_USER"), required("SCA_RECOVERY_TEST_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getInt(1); }
        } catch (java.sql.SQLException error) { throw new AssertionError("Cross-connection fixture inspection failed", error); }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "Missing required hosted PostgreSQL fixture " + name);
        assertFalse(value.isBlank(), "Missing required hosted PostgreSQL fixture " + name);
        return value;
    }
}
