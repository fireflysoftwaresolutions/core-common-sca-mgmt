/*
 * Copyright 2025 Firefly Software Foundation
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


package io.fireflysoftware.core.sca.web.recovery;

import io.fireflysoftware.core.sca.core.recovery.RecoveryProperties;
import io.fireflysoftware.core.sca.core.recovery.RecoveryUnavailableException;
import io.fireflysoftware.core.sca.core.recovery.SCARecoveryService;
import io.fireflysoftware.core.sca.interfaces.recovery.*;
import io.fireflysoftware.core.sca.web.controllers.recovery.SCARecoveryController;
import io.fireflysoftware.core.sca.web.controllers.recovery.RecoveryInputErrorHandler;
import org.fireflyframework.security.api.domain.Decision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SCARecoveryControllerTest {
    private WebTestClient client(SCARecoveryService service, RecoveryWebAuthorizationImpl authorization, boolean enabled) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (service != null) beans.addBean("service", service);
        beans.addBean("audit", RecoveryAuditFixture.qualified());
        if (authorization != null) beans.addBean("authorization", authorization);
        RecoveryProperties properties = new RecoveryProperties(); properties.setEnabled(enabled);
        return WebTestClient.bindToController(new SCARecoveryController(beans.getBeanProvider(SCARecoveryService.class),
                beans.getBeanProvider(RecoveryWebAuthorizationImpl.class), properties, beans.getBeanProvider(RecoveryBoundaryAuditPort.class))).build();
    }

    @Test
    void disabledFeatureNeedsNoServiceOrAuthorizationAndCannotReachCore() {
        client(null, null, false).post().uri("/api/v1/sca/recovery-operations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateRecoveryOperationRequest(RecoveryWebAuthorizationTest.binding(), "test-create"))
                .exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void missingPrincipalBindingForgeryAndPolicyDenyNeverCallCoreService() {
        SCARecoveryService service = mock(SCARecoveryService.class);
        for (var authorization : java.util.List.of(
                RecoveryWebAuthorizationTest.guard(Mono.empty(), (p, a, r, c) -> Mono.just(Decision.permit())),
                RecoveryWebAuthorizationTest.guard(Mono.just(RecoveryWebAuthorizationTest.principal(Map.of())), (p, a, r, c) -> Mono.just(Decision.deny("no current recovery permission"))))) {
            client(service, authorization, true).post().uri("/api/v1/sca/recovery-operations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new CreateRecoveryOperationRequest(RecoveryWebAuthorizationTest.binding(), "test-create"))
                    .exchange().expectStatus().is4xxClientError();
        }
        RecoveryBinding expected = RecoveryWebAuthorizationTest.binding();
        RecoveryBinding forged = new RecoveryBinding(expected.issuer(), "another-tenant", expected.clientId(), null,
                expected.actorSubject(), expected.targetIssuer(), expected.targetRealm(), expected.targetSubject(),
                expected.population(), expected.purpose(), expected.lockGeneration());
        var valid = RecoveryWebAuthorizationTest.guard(Mono.just(RecoveryWebAuthorizationTest.principal(Map.of())),
                (p, a, r, c) -> Mono.just(Decision.permit()));
        client(service, valid, true).post().uri("/api/v1/sca/recovery-operations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateRecoveryOperationRequest(forged, "test-create")).exchange().expectStatus().isForbidden();
        verifyNoInteractions(service);
    }

    @Test
    void admittedRequestsStillFailClosedWhenQualifiedCoreCapabilitiesAreUnavailable() {
        SCARecoveryService service = mock(SCARecoveryService.class);
        when(service.createRecoveryOperation(any())).thenReturn(Mono.error(new RecoveryUnavailableException()));
        var authorization = RecoveryWebAuthorizationTest.guard(Mono.just(RecoveryWebAuthorizationTest.principal(Map.of())),
                (p, a, r, c) -> Mono.just(Decision.permit()));
        client(service, authorization, true).post().uri("/api/v1/sca/recovery-operations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateRecoveryOperationRequest(RecoveryWebAuthorizationTest.binding(), "test-create"))
                .exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void rawProviderErrorsNeverBecomePublicErrorDetails() {
        SCARecoveryService service = mock(SCARecoveryService.class);
        when(service.createRecoveryOperation(any())).thenReturn(Mono.error(new IllegalStateException("synthetic-secret-provider-url")));
        var authorization = RecoveryWebAuthorizationTest.guard(Mono.just(RecoveryWebAuthorizationTest.principal(Map.of())),
                (p, a, r, c) -> Mono.just(Decision.permit()));
        client(service, authorization, true).post().uri("/api/v1/sca/recovery-operations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateRecoveryOperationRequest(RecoveryWebAuthorizationTest.binding(), "test-create"))
                .exchange().expectStatus().isEqualTo(503).expectBody().consumeWith(result -> {
                    byte[] bytes = result.getResponseBody();
                    if (bytes != null) org.assertj.core.api.Assertions.assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                            .doesNotContain("synthetic-secret-provider-url");
                });
    }

    @Test
    void malformedBodyProducesGenericBoundaryAuditWithoutBodyTargetProofOrServiceCall() {
        java.util.List<RecoveryBoundaryAuditPort.Event> events = new java.util.ArrayList<>();
        RecoveryBoundaryAuditPort audit = new RecoveryBoundaryAuditPort() {
            @Override public boolean isQualified() { return true; }
            @Override public Mono<Void> record(Event event) { return Mono.fromRunnable(() -> events.add(event)); }
        };
        SCARecoveryService service = mock(SCARecoveryService.class);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("service", service); beans.addBean("audit", audit);
        beans.addBean("authorization", RecoveryWebAuthorizationTest.guard(Mono.just(RecoveryWebAuthorizationTest.principal(Map.of())),
                (p, a, r, c) -> Mono.just(Decision.permit())));
        RecoveryProperties properties = new RecoveryProperties(); properties.setEnabled(true);
        var controller = new SCARecoveryController(beans.getBeanProvider(SCARecoveryService.class),
                beans.getBeanProvider(RecoveryWebAuthorizationImpl.class), properties, beans.getBeanProvider(RecoveryBoundaryAuditPort.class));
        WebTestClient.bindToController(controller)
                .controllerAdvice(new RecoveryInputErrorHandler(beans.getBeanProvider(RecoveryBoundaryAuditPort.class), properties)).build()
                .post().uri("/api/v1/sca/recovery-operations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{not-valid-json synthetic-private-proof").exchange().expectStatus().isBadRequest();
        org.assertj.core.api.Assertions.assertThat(events).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(events.getFirst().actor()).isNull();
        org.assertj.core.api.Assertions.assertThat(events.getFirst().action()).isNull();
        org.assertj.core.api.Assertions.assertThat(events.getFirst().outcome()).isEqualTo(RecoveryBoundaryAuditPort.Outcome.REFUSED);
        verifyNoInteractions(service);
    }

    @Test
    void approvedTypedRoutesDispatchOnlyAfterCallerAdmission() {
        SCARecoveryService service = mock(SCARecoveryService.class);
        UUID operation = UUID.randomUUID(), challenge = UUID.randomUUID(), execution = UUID.randomUUID();
        java.time.Instant expiry = RecoveryWebAuthorizationTest.NOW.plusSeconds(60);
        when(service.createRecoveryOperation(any())).thenReturn(Mono.just(new RecoveryOperationResponse(operation, expiry)));
        when(service.issueRecoveryChallenge(eq(operation), any())).thenReturn(Mono.just(new RecoveryChallengeResponse(
                challenge, RecoveryFactorCategory.POSSESSION, expiry, "masked-test-destination")));
        when(service.consumeRecoveryAuthorization(eq(operation), any())).thenReturn(Mono.just(new RecoveryAuthorizationReceipt(
                operation, execution, RecoveryWebAuthorizationTest.binding(), expiry)));
        when(service.recordRecoveryExecution(eq(operation), any())).thenReturn(Mono.empty());
        var authorization = RecoveryWebAuthorizationTest.guard(Mono.just(RecoveryWebAuthorizationTest.principal(Map.of())),
                (p, a, r, c) -> Mono.just(Decision.permit()));
        var client = client(service, authorization, true);
        String base = "/api/v1/sca/recovery-operations";
        client.post().uri(base).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateRecoveryOperationRequest(RecoveryWebAuthorizationTest.binding(), "test-create"))
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.operationId").isEqualTo(operation.toString());
        client.post().uri(base + "/" + operation + "/challenges").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new IssueRecoveryChallengeRequest(RecoveryWebAuthorizationTest.binding(), RecoveryFactorCategory.POSSESSION))
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.challengeId").isEqualTo(challenge.toString())
                .jsonPath("$.code").doesNotExist().jsonPath("$.response").doesNotExist();
        client.post().uri(base + "/" + operation + "/authorizations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ConsumeRecoveryAuthorizationRequest(RecoveryWebAuthorizationTest.binding(), "test-consume"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.executionId").isEqualTo(execution.toString());
        client.post().uri(base + "/" + operation + "/executions").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RecordRecoveryExecutionRequest(RecoveryWebAuthorizationTest.binding(), execution, RecoveryExecutionOutcome.UNKNOWN))
                .exchange().expectStatus().isNoContent();
        verify(service).recordRecoveryExecution(eq(operation), any());
    }

    @Test
    void verificationIsAcknowledgementOnlyAndDoesNotExposeVerdictBudgetOrSubmittedProof() {
        SCARecoveryService service = mock(SCARecoveryService.class);
        UUID operation = UUID.randomUUID();
        when(service.verifyRecoveryChallenge(eq(operation), any())).thenReturn(Mono.just(new RecoveryVerificationResponse(operation, "ACKNOWLEDGED")));
        var authorization = RecoveryWebAuthorizationTest.guard(Mono.just(RecoveryWebAuthorizationTest.principal(Map.of())),
                (p, a, r, c) -> Mono.just(Decision.permit()));
        client(service, authorization, true).post().uri("/api/v1/sca/recovery-operations/" + operation + "/verifications")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(new VerifyRecoveryChallengeRequest(RecoveryWebAuthorizationTest.binding(),
                        UUID.randomUUID(), RecoveryProofKind.POSSESSION_RESPONSE, "private-synthetic-proof"))
                .exchange().expectStatus().isAccepted().expectBody()
                .jsonPath("$.status").isEqualTo("ACKNOWLEDGED")
                .jsonPath("$.accepted").doesNotExist().jsonPath("$.attemptCount").doesNotExist()
                .jsonPath("$.remainingAttempts").doesNotExist().jsonPath("$.response").doesNotExist();
    }

    @Test
    void nativeReservationCompletionAndOnlineAuthorizationUseTypedAdmittedContracts() throws Exception {
        SCARecoveryService service = mock(SCARecoveryService.class);
        UUID operation = UUID.randomUUID(), challenge = UUID.randomUUID(), reservation = UUID.randomUUID(), execution = UUID.randomUUID();
        var expiry = RecoveryWebAuthorizationTest.NOW.plusSeconds(60);
        var binding = RecoveryWebAuthorizationTest.binding();
        when(service.reserveRecoveryVerification(eq(operation), any())).thenReturn(Mono.just(new RecoveryVerificationReservation(
                operation, challenge, reservation, "opaque-provider-ceremony", RecoveryFactorCategory.KNOWLEDGE, expiry)));
        when(service.completeRecoveryVerification(eq(operation), any())).thenReturn(Mono.just(new RecoveryVerificationResponse(operation, "ACKNOWLEDGED")));
        when(service.verifyRecoveryAuthorization(eq(operation), any())).thenReturn(Mono.just(new RecoveryAuthorizationReceipt(operation, execution, binding, expiry)));
        var authorization = RecoveryWebAuthorizationTest.guard(Mono.just(RecoveryWebAuthorizationTest.principal(Map.of())),
                (p, a, r, c) -> Mono.just(Decision.permit()));
        var client = client(service, authorization, true);
        String base = "/api/v1/sca/recovery-operations/" + operation;
        client.post().uri(base + "/verifications/reservations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ReserveRecoveryVerificationRequest(binding, challenge)).exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.reservationId").isEqualTo(reservation.toString()).jsonPath("$.providerChallengeId").isEqualTo("opaque-provider-ceremony");
        client.post().uri(base + "/verifications/complete").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CompleteRecoveryVerificationRequest(binding, challenge, reservation, "private-opaque-attestation"))
                .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.status").isEqualTo("ACKNOWLEDGED")
                .jsonPath("$.attestation").doesNotExist().jsonPath("$.accepted").doesNotExist();
        client.post().uri(base + "/authorizations/verify").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new VerifyRecoveryAuthorizationRequest(binding, execution, expiry)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.executionId").isEqualTo(execution.toString());
        for (var method : SCARecoveryController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)) {
                org.junit.jupiter.api.Assertions.assertTrue(method.isAnnotationPresent(
                        org.fireflyframework.web.idempotency.annotation.DisableIdempotency.class));
            }
        }
    }
}
