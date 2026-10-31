package io.fireflysoftware.core.sca.models.repositories;

import io.fireflysoftware.core.sca.models.entities.SCARecoveryAudit;
import io.fireflysoftware.core.sca.models.entities.SCARecoveryChallenge;
import io.fireflysoftware.core.sca.models.entities.SCARecoveryOperation;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.R2dbcException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Requires the explicitly installed disposable PostgreSQL fixture and its restricted runtime role. */
class SCARecoveryRepositoryIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static SCARecoveryOperationRepository operations;
    private static SCARecoveryChallengeRepository challenges;
    private static SCARecoveryAuditRepository audits;
    private static TransactionalOperator transactions;
    private static String jdbcUrl;
    private static String user;
    private static String password;

    @BeforeAll
    static void connectToRequiredFixture() throws SQLException {
        jdbcUrl = required("SCA_RECOVERY_TEST_JDBC_URL");
        user = required("SCA_RECOVERY_TEST_USER");
        password = required("SCA_RECOVERY_TEST_PASSWORD");
        ConnectionFactory connectionFactory = ConnectionFactories.get(ConnectionFactoryOptions
                .parse(required("SCA_RECOVERY_TEST_R2DBC_URL")).mutate()
                .option(ConnectionFactoryOptions.USER, user)
                .option(ConnectionFactoryOptions.PASSWORD, password).build());
        // No pool: concurrent transactional subscriptions acquire separate physical connections.
        R2dbcRepositoryFactory factory = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(connectionFactory));
        operations = factory.getRepository(SCARecoveryOperationRepository.class);
        challenges = factory.getRepository(SCARecoveryChallengeRepository.class);
        audits = factory.getRepository(SCARecoveryAuditRepository.class);
        transactions = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
        try (Connection connection = jdbc()) {
            assertEquals(user, scalar(connection, "SELECT current_user"));
            assertEquals("false", scalar(connection,
                    "SELECT has_table_privilege(current_user, 'public.sca_recovery_audit', 'UPDATE')::text"));
        }
    }

    @Test
    void concurrentReservationsSpendOneOperationAndChallengeAttemptAcrossConnections() {
        SCARecoveryOperation operation = operation(1, now().plusSeconds(600));
        save(operation);
        SCARecoveryChallenge challenge = challenge(operation, "POSSESSION", false, now().plusSeconds(500));
        challenge.setMaxAttempts(1);
        insertChallenge(challenge).block(TIMEOUT);
        Rendezvous bothLoaded = new Rendezvous();
        Mono<Boolean> first = attempt(operation.getId(), challenge.getId(), bothLoaded);
        Mono<Boolean> second = attempt(operation.getId(), challenge.getId(), bothLoaded);
        List<Boolean> outcomes = Mono.zip(first, second).map(pair -> List.of(pair.getT1(), pair.getT2())).block(TIMEOUT);
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        SCARecoveryOperation persisted = find(operation.getId());
        assertEquals(1, persisted.getAttemptCount());
        assertEquals("OPEN", persisted.getState());
        SCARecoveryChallenge reserved = challenges.findById(challenge.getId()).block(TIMEOUT);
        assertEquals(1, reserved.getAttemptCount());
        assertNotNull(reserved.getVerificationReservationId());
    }

    @Test
    void concurrentIssueReservationsSpendSingleLastDeliveryBudget() {
        SCARecoveryOperation operation = operation(3, now().plusSeconds(600));
        operation.setMaxChallenges(1);
        save(operation);
        Rendezvous bothLoaded = new Rendezvous();
        List<Boolean> outcomes = Mono.zip(reserveIssue(operation.getId(), bothLoaded),
                        reserveIssue(operation.getId(), bothLoaded))
                .map(pair -> List.of(pair.getT1(), pair.getT2())).block(TIMEOUT);
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, find(operation.getId()).getChallengeCount());
        assertEquals(0, find(operation.getId()).getAttemptCount());
    }

    @Test
    void concurrentConsumptionProducesExactlyOneExecutionAndAudit() throws SQLException {
        SCARecoveryOperation operation = readyOperation();
        Rendezvous bothLoaded = new Rendezvous();
        Mono<Boolean> first = consume(operation.getId(), UUID.randomUUID(), bothLoaded);
        Mono<Boolean> second = consume(operation.getId(), UUID.randomUUID(), bothLoaded);
        List<Boolean> outcomes = Mono.zip(first, second).map(pair -> List.of(pair.getT1(), pair.getT2())).block(TIMEOUT);
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        SCARecoveryOperation persisted = find(operation.getId());
        assertEquals("CONSUMED", persisted.getState());
        assertEquals(1, auditCount(operation.getId(), persisted.getExecutionId()));
    }

    @Test
    void databaseClockRejectsConsumptionThatExpiresAfterTheRead() throws SQLException {
        SCARecoveryOperation operation = operation(3, now().plusSeconds(10));
        save(operation);
        addProofs(operation);
        Mono<Void> delayed = operations.findById(operation.getId()).flatMap(loaded ->
                operations.databaseNow().flatMap(databaseNow -> {
                    assertTrue(databaseNow.isBefore(loaded.getExpiresAt()), "Fixture must be live before the race");
                    return Mono.delay(Duration.between(databaseNow, loaded.getExpiresAt()).plusMillis(100))
                            .then(Mono.defer(() -> saveConsumption(loaded, UUID.randomUUID(), true)));
                }));
        assertDatabaseState("23514", () -> transactions.transactional(delayed).block(TIMEOUT));
        assertEquals("OPEN", find(operation.getId()).getState());
        assertEquals(0, auditCount(operation.getId(), null));
    }

    @Test
    void failedAuditInsertRollsBackConsumption() throws SQLException {
        SCARecoveryOperation operation = readyOperation();
        UUID duplicateAuditId = UUID.randomUUID();
        audits.save(audit(duplicateAuditId, operation.getId(), "CREATED", null)).block(TIMEOUT);
        SCARecoveryOperation loaded = find(operation.getId());
        UUID executionId = UUID.randomUUID();
        setConsumed(loaded, executionId);
        Mono<Void> transition = operations.save(loaded).then(audits.save(
                audit(duplicateAuditId, operation.getId(), "CONSUMED", executionId))).then();
        assertDatabaseState("23505", () -> transactions.transactional(transition).block(TIMEOUT));
        SCARecoveryOperation after = find(operation.getId());
        assertEquals("OPEN", after.getState());
        assertNull(after.getExecutionId());
        assertEquals(0, auditCount(operation.getId(), executionId));
    }

    @Test
    void verificationExpiringAfterReservationPreservesSpentBudgets() {
        SCARecoveryOperation operation = operation(3, now().plusSeconds(600));
        save(operation);
        SCARecoveryChallenge challenge = challenge(operation, "POSSESSION", false, now().plusSeconds(500));
        challenge.setExpiresAt(now().plusSeconds(3));
        insertChallenge(challenge).block(TIMEOUT);
        UUID reservationId = UUID.randomUUID();
        reserveVerification(operation.getId(), challenge.getId(), reservationId).block(TIMEOUT);
        Mono<Void> lateProof = operations.findById(operation.getId()).flatMap(loadedOperation ->
                challenges.findById(challenge.getId()).flatMap(loadedChallenge ->
                        operations.databaseNow().flatMap(databaseNow -> {
                            assertTrue(databaseNow.isBefore(loadedChallenge.getExpiresAt()));
                            return Mono.delay(Duration.between(databaseNow, loadedChallenge.getExpiresAt()).plusMillis(100))
                                    .then(Mono.defer(() -> {
                                        loadedChallenge.setVerified(true);
                                        loadedChallenge.setAttestationId(UUID.randomUUID().toString());
                                        loadedChallenge.setProofExpiresAt(loadedChallenge.getExpiresAt());
                                        loadedChallenge.setVerificationReservationId(null);
                                        loadedChallenge.setVerificationReservedAt(null);
                                        return operations.save(loadedOperation).then(challenges.save(loadedChallenge))
                                                .then(audits.save(reservationAudit(operation.getId(), "PROOF_ACCEPTED", reservationId))).then();
                                    }));
                        })));
        assertDatabaseState("23514", () -> transactions.transactional(lateProof).block(TIMEOUT));
        assertEquals(1, find(operation.getId()).getAttemptCount());
        SCARecoveryChallenge after = challenges.findById(challenge.getId()).block(TIMEOUT);
        assertEquals(1, after.getAttemptCount());
        assertFalse(after.getVerified());
        assertEquals(reservationId, after.getVerificationReservationId());
    }

    @Test
    void deferredConstraintRejectsConsumptionWithoutAudit() {
        SCARecoveryOperation operation = readyOperation();
        SCARecoveryOperation loaded = find(operation.getId());
        assertDatabaseState("23514", () -> transactions.transactional(
                saveConsumption(loaded, UUID.randomUUID(), false)).block(TIMEOUT));
        assertEquals("OPEN", find(operation.getId()).getState());
    }

    @Test
    void expiredOrNonIndependentEvidenceCannotBeConsumed() {
        SCARecoveryOperation expiredProof = operation(3, now().plusSeconds(600));
        save(expiredProof);
        Instant proofExpiry = now().plusSeconds(5);
        insertChallenge(challenge(expiredProof, "POSSESSION", true, proofExpiry)).block(TIMEOUT);
        insertChallenge(challenge(expiredProof, "KNOWLEDGE", true, now().plusSeconds(500))).block(TIMEOUT);
        Instant databaseNow = now();
        if (databaseNow.isBefore(proofExpiry)) {
            Mono.delay(Duration.between(databaseNow, proofExpiry).plusMillis(100)).block(TIMEOUT);
        }
        assertConsumeDenied(expiredProof);

        SCARecoveryOperation sameDevice = operation(3, now().plusSeconds(600));
        save(sameDevice);
        SCARecoveryChallenge possession = challenge(sameDevice, "POSSESSION", true, now().plusSeconds(500));
        SCARecoveryChallenge knowledge = challenge(sameDevice, "KNOWLEDGE", true, now().plusSeconds(500));
        knowledge.setIndependenceId(possession.getIndependenceId());
        insertChallenge(possession).then(insertChallenge(knowledge)).block(TIMEOUT);
        assertConsumeDenied(sameDevice);
    }

    @Test
    void consumedProofCannotBeReusedOrReboundToAnotherLockGeneration() {
        SCARecoveryOperation operation = readyOperation();
        SCARecoveryOperation changedGeneration = find(operation.getId());
        changedGeneration.setLockGeneration("new-native-generation");
        assertDatabaseState("23514", () -> operations.save(changedGeneration).block(TIMEOUT));
        consume(operation.getId(), UUID.randomUUID(), null).block(TIMEOUT);
        SCARecoveryOperation replay = find(operation.getId());
        UUID originalExecution = replay.getExecutionId();
        assertDatabaseState("23514", () -> transactions.transactional(
                saveConsumption(replay, UUID.randomUUID(), true)).block(TIMEOUT));
        assertEquals(originalExecution, find(operation.getId()).getExecutionId());
    }

    @Test
    void providerAttestationCannotBeReusedAcrossOperations() {
        SCARecoveryOperation first = operation(3, now().plusSeconds(600));
        SCARecoveryOperation second = operation(3, now().plusSeconds(600));
        save(first);
        save(second);
        SCARecoveryChallenge accepted = challenge(first, "POSSESSION", true, now().plusSeconds(500));
        insertChallenge(accepted).block(TIMEOUT);
        SCARecoveryChallenge replay = challenge(second, "POSSESSION", true, now().plusSeconds(500));
        replay.setProviderId(accepted.getProviderId());
        replay.setAttestationId(accepted.getAttestationId());
        assertDatabaseState("23505", () -> insertChallenge(replay).block(TIMEOUT));
    }

    @Test
    void freshCreateKeyCannotResetExhaustedOrExpiredTargetGeneration() {
        for (boolean expired : List.of(false, true)) {
            SCARecoveryOperation original = operation(1, now().plusSeconds(expired ? -1 : 600));
            save(original);
            if (!expired) {
                SCARecoveryChallenge challenge = challenge(original, "POSSESSION", false, now().plusSeconds(500));
                insertChallenge(challenge).block(TIMEOUT);
                reserveVerification(original.getId(), challenge.getId(), UUID.randomUUID()).block(TIMEOUT);
                SCARecoveryOperation exhausted = find(original.getId());
                exhausted.setState("EXHAUSTED");
                operations.save(exhausted).block(TIMEOUT);
            }
            SCARecoveryOperation replacement = operation(1, now().plusSeconds(600));
            replacement.setTargetSubject(original.getTargetSubject());
            replacement.setActorSubject("different-actor");
            replacement.setClientId("different-client");
            assertNotEquals(original.getCreateKey(), replacement.getCreateKey());
            assertDatabaseState("23505", () -> operations.save(replacement).block(TIMEOUT));
            assertEquals(expired ? 0 : 1, find(original.getId()).getAttemptCount());
            assertEquals(expired ? "OPEN" : "EXHAUSTED", find(original.getId()).getState());
            SCARecoveryOperation newGeneration = operation(1, now().plusSeconds(600));
            newGeneration.setTargetSubject(original.getTargetSubject());
            newGeneration.setLockGeneration("new-authoritative-generation");
            save(newGeneration);
            assertEquals(0, find(newGeneration.getId()).getAttemptCount());
        }
    }

    @Test
    void providerFailureLeavesReservationAndSpentBudgetAcrossNewConnections() {
        SCARecoveryOperation operation = operation(3, now().plusSeconds(600));
        save(operation);
        SCARecoveryChallenge challenge = challenge(operation, "POSSESSION", false, now().plusSeconds(500));
        insertChallenge(challenge).block(TIMEOUT);
        UUID reservationId = UUID.randomUUID();
        reserveVerification(operation.getId(), challenge.getId(), reservationId).block(TIMEOUT);
        audits.save(reservationAudit(operation.getId(), "PROOF_REJECTED", reservationId)).block(TIMEOUT);
        assertDatabaseState("23514", () -> reserveVerification(
                operation.getId(), challenge.getId(), UUID.randomUUID()).block(TIMEOUT));
        assertEquals(1, find(operation.getId()).getAttemptCount());
        SCARecoveryChallenge after = challenges.findById(challenge.getId()).block(TIMEOUT);
        assertEquals(1, after.getAttemptCount());
        assertEquals(reservationId, after.getVerificationReservationId());
        assertFalse(after.getVerified());
    }

    @Test
    void runtimeCannotUpdateDeleteOrTruncateAudit() throws SQLException {
        SCARecoveryOperation operation = operation(3, now().plusSeconds(600));
        save(operation);
        UUID auditId = UUID.randomUUID();
        audits.save(audit(auditId, operation.getId(), "CREATED", null)).block(TIMEOUT);
        try (Connection connection = jdbc()) {
            for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE", "TRIGGER")) {
                assertEquals("false", scalar(connection,
                        "SELECT has_table_privilege(current_user, 'public.sca_recovery_audit', '" + privilege + "')::text"));
            }
            for (String sql : List.of(
                    "UPDATE public.sca_recovery_audit SET event_type = 'FORGED' WHERE id = ?",
                    "DELETE FROM public.sca_recovery_audit WHERE id = ?")) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setObject(1, auditId);
                    SQLException denied = assertThrows(SQLException.class, statement::executeUpdate);
                    assertEquals("42501", denied.getSQLState());
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("TRUNCATE public.sca_recovery_audit")) {
                assertEquals("42501", assertThrows(SQLException.class, statement::execute).getSQLState());
            }
        }
    }

    private static Mono<Boolean> attempt(UUID operationId, UUID challengeId, Rendezvous rendezvous) {
        Mono<Void> work = operations.findById(operationId).zipWith(challenges.findById(challengeId))
                .flatMap(pair -> rendezvous.arrive().then(Mono.defer(() -> {
                    SCARecoveryOperation operation = pair.getT1();
                    SCARecoveryChallenge challenge = pair.getT2();
                    UUID reservationId = UUID.randomUUID();
                    operation.setAttemptCount(operation.getAttemptCount() + 1);
                    challenge.setAttemptCount(challenge.getAttemptCount() + 1);
                    challenge.setVerificationReservationId(reservationId);
                    challenge.setVerificationReservedAt(Instant.now());
                    return operations.save(operation).then(challenges.save(challenge))
                            .then(audits.save(reservationAudit(operationId, "PROOF_RESERVED", reservationId))).then();
                })));
        return transactions.transactional(work).thenReturn(true)
                .onErrorResume(OptimisticLockingFailureException.class, error -> Mono.just(false));
    }

    private static Mono<Boolean> consume(UUID operationId, UUID executionId, Rendezvous rendezvous) {
        Mono<Void> work = operations.findById(operationId).flatMap(operation ->
                (rendezvous == null ? Mono.<Void>empty() : rendezvous.arrive())
                        .then(Mono.defer(() -> saveConsumption(operation, executionId, true))));
        return transactions.transactional(work).thenReturn(true)
                .onErrorResume(OptimisticLockingFailureException.class, error -> Mono.just(false));
    }

    private static Mono<Boolean> reserveIssue(UUID operationId, Rendezvous rendezvous) {
        Mono<Void> work = operations.findById(operationId).flatMap(operation ->
                rendezvous.arrive().then(Mono.defer(() -> {
                    operation.setChallengeCount(operation.getChallengeCount() + 1);
                    return operations.save(operation).then(audits.save(
                            reservationAudit(operationId, "CHALLENGE_RESERVED", UUID.randomUUID()))).then();
                })));
        return transactions.transactional(work).thenReturn(true)
                .onErrorResume(OptimisticLockingFailureException.class, error -> Mono.just(false));
    }

    private static Mono<Void> saveConsumption(SCARecoveryOperation operation, UUID executionId, boolean appendAudit) {
        setConsumed(operation, executionId);
        Mono<Void> save = operations.save(operation).then();
        return appendAudit ? save.then(audits.save(audit(UUID.randomUUID(), operation.getId(), "CONSUMED", executionId))).then() : save;
    }

    private static void setConsumed(SCARecoveryOperation operation, UUID executionId) {
        operation.setState("CONSUMED");
        operation.setExecutionId(executionId);
        operation.setConsumeKey("consume-" + executionId);
        Instant proposedExpiry = operation.getCreatedAt().plusSeconds(120);
        operation.setReceiptExpiresAt(proposedExpiry.isBefore(operation.getExpiresAt())
                ? proposedExpiry : operation.getExpiresAt());
        operation.setExecutionOutcome("PENDING");
    }

    private static void assertConsumeDenied(SCARecoveryOperation operation) {
        assertDatabaseState("23514", () -> consume(operation.getId(), UUID.randomUUID(), null).block(TIMEOUT));
        assertEquals("OPEN", find(operation.getId()).getState());
    }

    private static SCARecoveryOperation readyOperation() {
        SCARecoveryOperation operation = operation(3, now().plusSeconds(600));
        save(operation);
        addProofs(operation);
        return operation;
    }

    private static void addProofs(SCARecoveryOperation operation) {
        insertChallenge(challenge(operation, "POSSESSION", true, now().plusSeconds(500)))
                .then(insertChallenge(challenge(operation, "KNOWLEDGE", true, now().plusSeconds(500)))).block(TIMEOUT);
    }

    private static Mono<SCARecoveryChallenge> insertChallenge(SCARecoveryChallenge challenge) {
        boolean acceptAfterIssue = challenge.getVerified();
        String attestationId = challenge.getAttestationId();
        Instant proofExpiry = challenge.getProofExpiresAt();
        challenge.setVerified(false);
        challenge.setAttemptCount(0);
        challenge.setAttestationId(null);
        challenge.setProofExpiresAt(null);
        UUID verificationReservation = UUID.randomUUID();
        Mono<Void> issueReservation = operations.findById(challenge.getOperationId()).flatMap(operation -> {
            operation.setChallengeCount(operation.getChallengeCount() + 1);
            return operations.save(operation).then(audits.save(reservationAudit(operation.getId(),
                    "CHALLENGE_RESERVED", challenge.getIssueReservationId()))).then();
        });
        Mono<SCARecoveryChallenge> issued = transactions.transactional(issueReservation).then(challenges.save(challenge));
        if (!acceptAfterIssue) {
            return issued;
        }
        return issued.then(reserveVerification(challenge.getOperationId(), challenge.getId(), verificationReservation))
                .then(Mono.defer(() -> transactions.transactional(operations.findById(challenge.getOperationId())
                        .flatMap(operation -> challenges.findById(challenge.getId()).flatMap(loaded -> {
                            loaded.setVerified(true);
                            loaded.setAttestationId(attestationId);
                            loaded.setProofExpiresAt(proofExpiry);
                            loaded.setVerificationReservationId(null);
                            loaded.setVerificationReservedAt(null);
                            return operations.save(operation).then(challenges.save(loaded)).flatMap(saved ->
                                    audits.save(reservationAudit(operation.getId(), "PROOF_ACCEPTED", verificationReservation))
                                            .thenReturn(saved));
                        })))));
    }

    private static Mono<Void> reserveVerification(UUID operationId, UUID challengeId, UUID reservationId) {
        return transactions.transactional(operations.findById(operationId).flatMap(operation ->
                challenges.findById(challengeId).flatMap(challenge -> operations.databaseNow().flatMap(databaseNow -> {
                    operation.setAttemptCount(operation.getAttemptCount() + 1);
                    challenge.setAttemptCount(challenge.getAttemptCount() + 1);
                    challenge.setVerificationReservationId(reservationId);
                    challenge.setVerificationReservedAt(databaseNow);
                    return operations.save(operation).then(challenges.save(challenge))
                            .then(audits.save(reservationAudit(operationId, "PROOF_RESERVED", reservationId))).then();
                }))));
    }

    private static SCARecoveryAudit reservationAudit(UUID operationId, String event, UUID reservationId) {
        SCARecoveryAudit audit = audit(UUID.randomUUID(), operationId, event, null);
        audit.setReservationId(reservationId);
        return audit;
    }

    private static SCARecoveryOperation operation(int maxAttempts, Instant expiresAt) {
        SCARecoveryOperation operation = new SCARecoveryOperation();
        operation.setId(UUID.randomUUID());
        operation.setIssuer("fixture-issuer");
        operation.setTenant("opaque-tenant");
        operation.setClientId("fixture-client");
        operation.setActorSubject("fixture-actor");
        operation.setTargetIssuer("fixture-target-issuer");
        operation.setTargetRealm("fixture-realm");
        operation.setTargetSubject("fixture-target-" + UUID.randomUUID());
        operation.setPopulation("CUSTOMER");
        operation.setPurpose("PERMANENT_AUTHENTICATION_UNLOCK");
        operation.setLockGeneration("fixture-generation");
        operation.setOwnerLinkId("fixture-owner-link");
        operation.setIdentityLinkAttestationId("fixture-identity-link-attestation");
        operation.setLockAttestationId("fixture-lock-attestation");
        operation.setCreateKey(UUID.randomUUID().toString());
        operation.setState("OPEN");
        operation.setCreatedAt(now().minusSeconds(60));
        operation.setExpiresAt(expiresAt);
        operation.setAttemptCount(0);
        operation.setMaxAttempts(maxAttempts);
        operation.setChallengeCount(0);
        operation.setMaxChallenges(3);
        return operation;
    }

    private static SCARecoveryChallenge challenge(SCARecoveryOperation operation, String category,
                                                 boolean verified, Instant proofExpiresAt) {
        SCARecoveryChallenge challenge = new SCARecoveryChallenge();
        challenge.setId(UUID.randomUUID());
        challenge.setOperationId(operation.getId());
        challenge.setCategory(category);
        challenge.setProviderId("fixture-provider");
        challenge.setEnrollmentId(UUID.randomUUID().toString());
        challenge.setIndependenceId(UUID.randomUUID().toString());
        challenge.setProviderChallengeId(UUID.randomUUID().toString());
        challenge.setIssueReservationId(UUID.randomUUID());
        challenge.setCreatedAt(now().minusSeconds(30));
        challenge.setExpiresAt(now().plusSeconds(510));
        challenge.setAttemptCount(verified ? 1 : 0);
        challenge.setMaxAttempts(3);
        challenge.setVerified(verified);
        if (verified) {
            challenge.setAttestationId(UUID.randomUUID().toString());
            challenge.setProofExpiresAt(proofExpiresAt);
        }
        return challenge;
    }

    private static SCARecoveryAudit audit(UUID id, UUID operationId, String type, UUID executionId) {
        SCARecoveryAudit audit = new SCARecoveryAudit();
        audit.setId(id);
        audit.setOperationId(operationId);
        audit.setEventType(type);
        audit.setOccurredAt(Instant.now());
        audit.setExecutionId(executionId);
        return audit;
    }

    private static void save(SCARecoveryOperation operation) {
        operations.save(operation).block(TIMEOUT);
    }

    private static SCARecoveryOperation find(UUID id) {
        return operations.findById(id).block(TIMEOUT);
    }

    private static Instant now() {
        return operations.databaseNow().block(TIMEOUT);
    }

    private static Connection jdbc() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static int auditCount(UUID operationId, UUID executionId) throws SQLException {
        try (Connection connection = jdbc(); PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM public.sca_recovery_audit WHERE event_type = 'CONSUMED' AND operation_id = ? AND (?::uuid IS NULL OR execution_id = ?::uuid)")) {
            statement.setObject(1, operationId);
            statement.setObject(2, executionId);
            statement.setObject(3, executionId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "Required PostgreSQL fixture environment variable: " + name);
        assertFalse(value.isBlank(), "Required PostgreSQL fixture environment variable: " + name);
        return value;
    }

    private static void assertDatabaseState(String expected, Executable action) {
        Throwable error = assertThrows(RuntimeException.class, action);
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof R2dbcException databaseError) {
                assertEquals(expected, databaseError.getSqlState());
                return;
            }
        }
        fail("Expected PostgreSQL SQLSTATE " + expected + ", got " + error.getClass().getName());
    }

    private static final class Rendezvous {
        private final AtomicInteger arrivals = new AtomicInteger();
        private final Sinks.One<Void> released = Sinks.one();

        Mono<Void> arrive() {
            if (arrivals.incrementAndGet() == 2) {
                released.tryEmitEmpty();
            }
            return released.asMono().timeout(TIMEOUT);
        }
    }
}
