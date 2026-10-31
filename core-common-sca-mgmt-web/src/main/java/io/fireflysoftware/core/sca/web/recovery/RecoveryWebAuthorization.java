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
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies caller expectations only; core owner/native admission remains a separate mandatory gate. */
@Component
@ConditionalOnProperty(name = "firefly.sca.recovery.enabled", havingValue = "true")
public final class RecoveryWebAuthorization {
    private final SecurityContextPort securityContext;
    private final PolicyDecisionPort policy;
    private final RecoveryBoundaryAuditPort audit;
    private final String issuer;
    private final String audience;
    private final Set<String> clients;
    private final Set<String> populations;
    private final Clock clock;

    @Autowired
    public RecoveryWebAuthorization(SecurityContextPort securityContext, PolicyDecisionPort policy, RecoveryBoundaryAuditPort audit,
            @Value("${firefly.sca.recovery.receiver.issuer}") String issuer,
            @Value("${firefly.sca.recovery.receiver.audience}") String audience,
            @Value("${firefly.sca.recovery.receiver.allowed-clients}") List<String> clients,
            @Value("${firefly.sca.recovery.receiver.allowed-populations}") List<String> populations) {
        this(securityContext, policy, audit, issuer, audience, clients, populations, Clock.systemUTC());
    }

    public RecoveryWebAuthorization(SecurityContextPort securityContext, PolicyDecisionPort policy, RecoveryBoundaryAuditPort audit, String issuer,
            String audience, List<String> clients, List<String> populations, Clock clock) {
        if (securityContext == null || policy == null || blank(issuer) || blank(audience) || clock == null
                || invalidList(clients) || invalidList(populations) || !Set.of("HUMAN", "STAFF").containsAll(populations)) {
            throw new IllegalArgumentException("Explicit verified recovery caller trust and direct-client/population allowlists required");
        }
        if (audit == null || !audit.isQualified()) throw new IllegalStateException("Qualified durable recovery boundary audit required");
        this.audit = audit;
        this.securityContext = securityContext;
        this.policy = policy;
        this.issuer = issuer;
        this.audience = audience;
        this.clients = Set.copyOf(clients);
        this.populations = Set.copyOf(populations);
        this.clock = clock;
    }

    public Mono<Void> authorize(RecoveryBinding expectations, RecoveryAction action, UUID operationId) {
        return Mono.defer(() -> {
            AtomicReference<RecoveryBoundaryAuditPort.VerifiedActor> verifiedActor = new AtomicReference<>();
            Mono<Void> admission = Mono.defer(securityContext::currentPrincipal)
                .switchIfEmpty(Mono.error(new Refusal(HttpStatus.UNAUTHORIZED)))
                .flatMap(principal -> {
                    if (action == null || action != RecoveryAction.CREATE && operationId == null) throw new Refusal(HttpStatus.FORBIDDEN);
                    validate(principal, expectations);
                    verifiedActor.set(new RecoveryBoundaryAuditPort.VerifiedActor(principal.issuer(), principal.tenantId(),
                            principal.subject(), principal.claim("client_id", String.class), principal.claim("principal_kind", String.class)));
                    Map<String, Object> context = new HashMap<>();
                    context.put("issuer", principal.issuer());
                    context.put("tenantId", principal.tenantId());
                    context.put("actorSubject", principal.subject());
                    context.put("clientId", principal.claim("client_id", String.class));
                    context.put("principalKind", principal.claim("principal_kind", String.class));
                    context.put("operationId", operationId == null ? "new" : operationId.toString());
                    context.put("expectedTargetIssuer", expectations.targetIssuer());
                    context.put("expectedTargetRealm", expectations.targetRealm());
                    context.put("expectedTargetSubject", expectations.targetSubject());
                    context.put("expectedLockGeneration", expectations.lockGeneration());
                    return Mono.defer(() -> policy.authorize(principal, policyAction(action),
                                    "sca.recovery:" + expectations.purpose(), Map.copyOf(context)))
                            .timeout(Duration.ofSeconds(5))
                            .filter(decision -> decision.granted() && decision.obligations().isEmpty())
                            .switchIfEmpty(Mono.error(new Refusal(HttpStatus.FORBIDDEN)))
                            .map(decision -> { validate(principal, expectations); return decision; });
                }).then().onErrorMap(error -> error instanceof Refusal ? error : new Refusal(HttpStatus.FORBIDDEN));
            return admission.materialize().flatMap(signal -> {
                var outcome = signal.isOnError() ? RecoveryBoundaryAuditPort.Outcome.REFUSED : RecoveryBoundaryAuditPort.Outcome.ADMITTED;
                return Mono.defer(() -> audit.record(new RecoveryBoundaryAuditPort.Event(verifiedActor.get(), action, outcome)))
                        .timeout(Duration.ofSeconds(5))
                        .onErrorMap(error -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recovery boundary audit unavailable"))
                        .then(signal.isOnError() ? Mono.<Void>error(signal.getThrowable()) : Mono.<Void>empty());
            });
        });
    }

    private void validate(SecurityPrincipal principal, RecoveryBinding expected) {
        Map<String, Object> claims = principal.claims();
        Instant now = clock.instant();
        if (expected == null || !issuer.equals(principal.issuer()) || !issuer.equals(expected.issuer())
                || blank(principal.subject()) || !principal.subject().equals(expected.actorSubject())
                || blank(principal.tenantId()) || !principal.tenantId().equals(claims.get("tenant_id"))
                || !principal.tenantId().equals(expected.tenant()) || expected.delegatingClientId() != null
                || !(claims.get("client_id") instanceof String client) || blank(client)
                || !clients.contains(client) || !client.equals(expected.clientId())
                || !(claims.get("principal_kind") instanceof String population)
                || !populations.contains(population) || !population.equals(expected.population())
                || !(claims.get("aud") instanceof Collection<?> audiences) || !audiences.contains(audience)
                || !(claims.get("exp") instanceof Instant expiration) || !expiration.isAfter(now)
                || claims.containsKey("nbf") && (!(claims.get("nbf") instanceof Instant notBefore) || notBefore.isAfter(now))) {
            throw new Refusal(HttpStatus.FORBIDDEN);
        }
    }

    private static String policyAction(RecoveryAction action) {
        return switch (action) {
            case CREATE -> "sca.recovery.create";
            case ISSUE_CHALLENGE -> "sca.recovery.challenge.issue";
            case VERIFY_CHALLENGE -> "sca.recovery.challenge.verify";
            case CONSUME -> "sca.recovery.authorization.consume";
            case RECORD_EXECUTION -> "sca.recovery.execution.record";
        };
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean invalidList(List<String> values) {
        return values == null || values.isEmpty() || values.stream().anyMatch(value -> blank(value) || !value.equals(value.strip()));
    }
    private static final class Refusal extends ResponseStatusException {
        private Refusal(HttpStatus status) { super(status, "Verified recovery caller and current policy required"); }
    }
}
