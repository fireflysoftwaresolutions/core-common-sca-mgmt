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

import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryAction;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryBinding;
import org.fireflyframework.security.api.domain.Decision;
import org.fireflyframework.security.api.domain.Obligation;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoveryWebAuthorizationTest {
    static final String ISSUER = "https://identity.example/realms/restricted-recovery";
    static final String CLIENT = "explicit-test-client";
    static final Instant NOW = Instant.parse("2026-10-31T20:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static RecoveryBinding binding() {
        return new RecoveryBinding(ISSUER, "opaque/tenant:one", CLIENT, null, "opaque/actor:one",
                "https://identity.example", "customers", "opaque/target:one", "HUMAN", "native-recovery-test-purpose", "native-generation");
    }

    static SecurityPrincipal principal(Map<String, Object> changed) {
        Map<String, Object> claims = new HashMap<>(Map.of("tenant_id", "opaque/tenant:one", "client_id", CLIENT,
                "principal_kind", "HUMAN", "aud", List.of("core-recovery"), "exp", NOW.plusSeconds(300)));
        claims.putAll(changed);
        return SecurityPrincipal.builder().issuer(ISSUER).tenantId("opaque/tenant:one").subject("opaque/actor:one").claims(claims).build();
    }

    static RecoveryWebAuthorizationImpl guard(Mono<SecurityPrincipal> principal, PolicyDecisionPort policy) {
        return new RecoveryWebAuthorizationImpl(() -> principal, policy, RecoveryAuditFixture.qualified(), ISSUER, "core-recovery", List.of(CLIENT), List.of("HUMAN"), CLOCK);
    }

    private void refused(Mono<?> result, HttpStatus status) {
        StepVerifier.create(result).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(status);
        }).verify();
    }

    @Test
    void absentPrincipalAndBadSignedProofNeverReachPolicy() {
        for (Mono<SecurityPrincipal> actor : List.of(Mono.<SecurityPrincipal>empty(),
                Mono.just(principal(Map.of("exp", NOW))), Mono.just(principal(Map.of("exp", 1L))),
                Mono.just(principal(Map.of("nbf", NOW.plusSeconds(1)))), Mono.just(principal(Map.of("nbf", 1L))),
                Mono.just(principal(Map.of("client_id", "other-client"))), Mono.just(principal(Map.of("client_id", 1L))),
                Mono.just(principal(Map.of("principal_kind", "MACHINE"))),
                Mono.just(principal(Map.of("principal_kind", "STAFF"))),
                Mono.just(principal(Map.of()).toBuilder().issuer("https://untrusted.example").build()),
                Mono.just(principal(Map.of()).toBuilder().subject("another-actor").build()), Mono.just(principal(Map.of("aud", List.of("wrong-service")))),
                Mono.just(principal(Map.of("tenant_id", "wrong-tenant"))))) {
            AtomicInteger calls = new AtomicInteger();
            var authorization = guard(actor, (p, a, r, c) -> { calls.incrementAndGet(); return Mono.just(Decision.permit()); });
            StepVerifier.create(authorization.authorize(binding(), RecoveryAction.CREATE, null)).expectError(ResponseStatusException.class).verify();
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void eachBindingCallerExpectationMustMatchVerifiedCaller() {
        RecoveryBinding good = binding();
        for (RecoveryBinding changed : List.of(
                new RecoveryBinding("https://wrong.example", good.tenant(), good.clientId(), null, good.actorSubject(), good.targetIssuer(), good.targetRealm(), good.targetSubject(), good.population(), good.purpose(), good.lockGeneration()),
                new RecoveryBinding(good.issuer(), "other-tenant", good.clientId(), null, good.actorSubject(), good.targetIssuer(), good.targetRealm(), good.targetSubject(), good.population(), good.purpose(), good.lockGeneration()),
                new RecoveryBinding(good.issuer(), good.tenant(), "other-client", null, good.actorSubject(), good.targetIssuer(), good.targetRealm(), good.targetSubject(), good.population(), good.purpose(), good.lockGeneration()),
                new RecoveryBinding(good.issuer(), good.tenant(), good.clientId(), "unverified-delegator", good.actorSubject(), good.targetIssuer(), good.targetRealm(), good.targetSubject(), good.population(), good.purpose(), good.lockGeneration()),
                new RecoveryBinding(good.issuer(), good.tenant(), good.clientId(), null, "other-actor", good.targetIssuer(), good.targetRealm(), good.targetSubject(), good.population(), good.purpose(), good.lockGeneration()),
                new RecoveryBinding(good.issuer(), good.tenant(), good.clientId(), null, good.actorSubject(), good.targetIssuer(), good.targetRealm(), good.targetSubject(), "STAFF", good.purpose(), good.lockGeneration()))) {
            AtomicInteger calls = new AtomicInteger();
            var authorization = guard(Mono.just(principal(Map.of())), (p, a, r, c) -> { calls.incrementAndGet(); return Mono.just(Decision.permit()); });
            refused(authorization.authorize(changed, RecoveryAction.CREATE, null), HttpStatus.FORBIDDEN);
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void policyReceivesExactPurposeActionAndVerifiedCallerWithoutCreatingOwnerProof() {
        UUID operation = UUID.randomUUID();
        var authorization = guard(Mono.just(principal(Map.of())), (p, action, resource, context) -> {
            assertThat(action).isEqualTo("sca.recovery.authorization.consume");
            assertThat(resource).isEqualTo("sca.recovery:native-recovery-test-purpose");
            assertThat(context).containsEntry("operationId", operation.toString()).containsEntry("clientId", CLIENT)
                    .containsEntry("tenantId", "opaque/tenant:one").containsEntry("actorSubject", "opaque/actor:one");
            return Mono.just(Decision.permit());
        });
        StepVerifier.create(authorization.authorize(binding(), RecoveryAction.CONSUME, operation)).verifyComplete();
    }

    @Test
    void allNonPermitsAndUnsupportedObligationsFailClosed() {
        for (Mono<Decision> result : List.of(Mono.just(Decision.deny("private")), Mono.just(Decision.indeterminate("private")),
                Mono.<Decision>empty(), Mono.<Decision>error(new IllegalStateException("private-provider-url")),
                Mono.just(Decision.permit(List.of(Obligation.of("unfulfilled", Map.of())))))) {
            refused(guard(Mono.just(principal(Map.of())), (p, a, r, c) -> result)
                    .authorize(binding(), RecoveryAction.VERIFY_CHALLENGE, UUID.randomUUID()), HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void proofThatExpiresDuringPolicyDecisionIsRefused() {
        Clock changing = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(changing.instant()).thenReturn(NOW, NOW.plusSeconds(301));
        var authorization = new RecoveryWebAuthorizationImpl(() -> Mono.just(principal(Map.of())),
                (p, a, r, c) -> Mono.just(Decision.permit()), RecoveryAuditFixture.qualified(), ISSUER, "core-recovery", List.of(CLIENT), List.of("HUMAN"), changing);
        refused(authorization.authorize(binding(), RecoveryAction.CREATE, null), HttpStatus.FORBIDDEN);
    }

    @Test
    void boundaryAuditRecordsOnlySafeCallerOutcomeAndIsMandatoryBeforeAdmission() {
        for (boolean missing : new boolean[]{true, false}) {
            java.util.List<RecoveryBoundaryAuditPort.Event> events = new java.util.ArrayList<>();
            RecoveryBoundaryAuditPort audit = new RecoveryBoundaryAuditPort() {
                @Override public boolean isQualified() { return true; }
                @Override public Mono<Void> record(Event event) { return Mono.fromRunnable(() -> events.add(event)); }
            };
            var authorization = new RecoveryWebAuthorizationImpl(() -> missing ? Mono.empty() : Mono.just(principal(Map.of())),
                    (p, a, r, c) -> Mono.just(Decision.deny("private-policy-url")), audit,
                    ISSUER, "core-recovery", List.of(CLIENT), List.of("HUMAN"), CLOCK);
            StepVerifier.create(authorization.authorize(binding(), RecoveryAction.CREATE, null)).expectError(ResponseStatusException.class).verify();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().outcome()).isEqualTo(RecoveryBoundaryAuditPort.Outcome.REFUSED);
            assertThat(events.getFirst().action()).isEqualTo(RecoveryAction.CREATE);
            if (missing) assertThat(events.getFirst().actor()).isNull();
            else assertThat(events.getFirst().actor().clientId()).isEqualTo(CLIENT);
            assertThat(events.getFirst().toString()).doesNotContain(binding().targetSubject(), "private-policy-url");
        }
        AtomicInteger admitted = new AtomicInteger();
        RecoveryBoundaryAuditPort successful = new RecoveryBoundaryAuditPort() {
            @Override public boolean isQualified() { return true; }
            @Override public Mono<Void> record(Event event) {
                return Mono.fromRunnable(() -> {
                    assertThat(event.outcome()).isEqualTo(Outcome.ADMITTED);
                    assertThat(event.actor().clientId()).isEqualTo(CLIENT);
                    admitted.incrementAndGet();
                });
            }
        };
        var permitted = new RecoveryWebAuthorizationImpl(() -> Mono.just(principal(Map.of())), (p, a, r, c) -> Mono.just(Decision.permit()),
                successful, ISSUER, "core-recovery", List.of(CLIENT), List.of("HUMAN"), CLOCK);
        StepVerifier.create(permitted.authorize(binding(), RecoveryAction.CREATE, null)).verifyComplete();
        assertThat(admitted).hasValue(1);
        RecoveryBoundaryAuditPort unavailable = event -> Mono.empty();
        assertThatThrownBy(() -> new RecoveryWebAuthorizationImpl(() -> Mono.empty(), (p, a, r, c) -> Mono.just(Decision.permit()),
                unavailable, ISSUER, "core-recovery", List.of(CLIENT), List.of("HUMAN"), CLOCK)).isInstanceOf(IllegalStateException.class);
        RecoveryBoundaryAuditPort failed = new RecoveryBoundaryAuditPort() {
            @Override public boolean isQualified() { return true; }
            @Override public Mono<Void> record(Event event) { return Mono.error(new IllegalStateException("private-audit-detail")); }
        };
        var authorization = new RecoveryWebAuthorizationImpl(() -> Mono.just(principal(Map.of())), (p, a, r, c) -> Mono.just(Decision.permit()),
                failed, ISSUER, "core-recovery", List.of(CLIENT), List.of("HUMAN"), CLOCK);
        refused(authorization.authorize(binding(), RecoveryAction.CREATE, null), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void missingProofNoAzpFallbackAndUnconfiguredAllowlistDeny() {
        for (String missing : List.of("tenant_id", "exp", "principal_kind")) {
            Map<String, Object> absent = new HashMap<>(principal(Map.of()).claims()); absent.remove(missing);
            AtomicInteger calls = new AtomicInteger();
            refused(guard(Mono.just(principal(Map.of()).toBuilder().claims(absent).build()), (p, a, r, c) -> {
                calls.incrementAndGet(); return Mono.just(Decision.permit());
            }).authorize(binding(), RecoveryAction.CREATE, null), HttpStatus.FORBIDDEN);
            assertThat(calls).hasValue(0);
        }
        Map<String, Object> claims = new HashMap<>(principal(Map.of()).claims());
        claims.remove("client_id"); claims.put("azp", CLIENT);
        refused(guard(Mono.just(principal(Map.of()).toBuilder().claims(claims).build()), (p, a, r, c) -> Mono.just(Decision.permit()))
                .authorize(binding(), RecoveryAction.CREATE, null), HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> new RecoveryWebAuthorizationImpl(() -> Mono.empty(), (p, a, r, c) -> Mono.just(Decision.permit()),
                RecoveryAuditFixture.qualified(), ISSUER, "core-recovery", List.of(), List.of("HUMAN"), CLOCK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecoveryWebAuthorizationImpl(() -> Mono.empty(), (p, a, r, c) -> Mono.just(Decision.permit()),
                RecoveryAuditFixture.qualified(), ISSUER, "core-recovery", List.of(CLIENT), List.of("MACHINE"), CLOCK)).isInstanceOf(IllegalArgumentException.class);
    }
}
