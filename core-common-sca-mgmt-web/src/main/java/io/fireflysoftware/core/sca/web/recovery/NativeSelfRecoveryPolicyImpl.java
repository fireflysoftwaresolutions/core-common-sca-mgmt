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

package io.fireflysoftware.core.sca.web.recovery;

import org.fireflyframework.security.api.domain.Decision;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Narrow product self-recovery policy; native current-state admission remains independently mandatory. */
@Component("nativeSelfRecoveryPolicy")
@ConditionalOnProperty(name = {"firefly.sca.recovery.enabled", "firefly.sca.recovery.native-owner.enabled",
        "firefly.sca.recovery.native-self-policy.enabled"}, havingValue = "true")
public final class NativeSelfRecoveryPolicyImpl implements PolicyDecisionPort {
    private static final String PURPOSE = "PERMANENT_AUTHENTICATION_UNLOCK";
    private static final Set<String> ACTIONS = Set.of("sca.recovery.create", "sca.recovery.challenge.issue",
            "sca.recovery.challenge.verify", "sca.recovery.verification.reserve", "sca.recovery.verification.complete",
            "sca.recovery.authorization.consume", "sca.recovery.authorization.verify", "sca.recovery.execution.record");
    private final String issuer;
    private final String realm;
    private final Set<String> clients;
    private final Set<String> populations;
    private final Set<String> audiences;
    private final Clock clock;

    @Autowired
    public NativeSelfRecoveryPolicyImpl(@Value("${firefly.sca.recovery.receiver.issuer}") String issuer,
            @Value("${firefly.sca.recovery.native-self-policy.realm}") String realm,
            @Value("${firefly.sca.recovery.receiver.allowed-clients}") List<String> clients,
            @Value("${firefly.sca.recovery.receiver.allowed-populations}") List<String> populations,
            @Value("${firefly.sca.recovery.native-self-policy.actor-audiences}") List<String> audiences) {
        this(issuer, realm, clients, populations, audiences, Clock.systemUTC());
    }
    NativeSelfRecoveryPolicyImpl(String issuer, String realm, List<String> clients, List<String> populations,
            List<String> audiences, Clock clock) {
        if (issuer == null || !issuer.startsWith("https://") || realm == null || realm.isBlank()
                || !issuer.endsWith("/realms/" + realm) || clients == null || clients.isEmpty()
                || populations == null || populations.size() != 1 || !Set.of("HUMAN", "STAFF").containsAll(populations)
                || audiences == null || Set.copyOf(audiences).size() != 2 || clock == null
                || java.util.stream.Stream.of(clients, populations, audiences).flatMap(Collection::stream).anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalStateException("Explicit single-population native self-recovery policy required");
        }
        this.issuer = issuer; this.realm = realm; this.clients = Set.copyOf(clients);
        this.populations = Set.copyOf(populations); this.audiences = Set.copyOf(audiences); this.clock = clock;
    }

    @Override public Mono<Decision> authorize(SecurityPrincipal principal, String action, String resource, Map<String, Object> context) {
        return Mono.fromSupplier(() -> {
            try { return permitted(principal, action, resource, context) ? Decision.permit() : Decision.deny("Native self-recovery refused"); }
            catch (RuntimeException malformed) { return Decision.deny("Native self-recovery refused"); }
        });
    }
    private boolean permitted(SecurityPrincipal actor, String action, String resource, Map<String, Object> context) {
        if (actor == null || context == null || !ACTIONS.contains(action) || !("sca.recovery:" + PURPOSE).equals(resource)) return false;
        Map<String, Object> claims = actor.claims(); Instant now = clock.instant();
        Object rawPopulation = claims.get("principal_kind"), rawClient = claims.get("client_id");
        if (!(rawPopulation instanceof String population) || !populations.contains(population)
                || !(rawClient instanceof String client) || !clients.contains(client)
                || !issuer.equals(actor.issuer()) || !text(actor.subject()) || !text(actor.tenantId())
                || !actor.tenantId().equals(claims.get("tenant_id")) || !Boolean.TRUE.equals(claims.get("recovery_only"))
                || !PURPOSE.equals(claims.get("recovery_purpose")) || !"firefly.recovery".equals(claims.get("scope"))
                || !"firefly-restricted-recovery".equals(claims.get("typ")) || !realm.equals(claims.get("recovery_realm"))
                || claims.containsKey("roles") || claims.containsKey("authorities")
                || !text(claims.get("recovery_lock_generation")) || !text(claims.get("recovery_ceremony_id"))
                || !claims.get("recovery_ceremony_id").equals(claims.get("jti"))
                || !(claims.get("aud") instanceof Collection<?> actualAudiences) || !audiences.equals(Set.copyOf(actualAudiences))
                || !(claims.get("iat") instanceof Instant issued) || issued.isAfter(now)
                || !(claims.get("exp") instanceof Instant expires) || !now.isBefore(expires) || !issued.isBefore(expires)
                || Duration.between(issued, expires).compareTo(Duration.ofSeconds("STAFF".equals(population) ? 180 : 300)) > 0
                || claims.containsKey("nbf") && (!(claims.get("nbf") instanceof Instant notBefore) || now.isBefore(notBefore))) return false;
        String linkName = "HUMAN".equals(population) ? "party_id" : "staff_actor_id";
        if (claims.containsKey("HUMAN".equals(population) ? "staff_actor_id" : "party_id") || !(claims.get(linkName) instanceof String link)
                || !UUID.fromString(link).toString().equals(link)) return false;
        if (!issuer.equals(context.get("issuer")) || !actor.tenantId().equals(context.get("tenantId"))
                || !actor.subject().equals(context.get("actorSubject")) || !client.equals(context.get("clientId"))
                || !population.equals(context.get("principalKind")) || !issuer.equals(context.get("expectedTargetIssuer"))
                || !realm.equals(context.get("expectedTargetRealm")) || !actor.subject().equals(context.get("expectedTargetSubject"))
                || !claims.get("recovery_lock_generation").equals(context.get("expectedLockGeneration"))) return false;
        Object operation = context.get("operationId");
        return "sca.recovery.create".equals(action) ? "new".equals(operation)
                : operation instanceof String value && UUID.fromString(value).toString().equals(value);
    }
    private static boolean text(Object value) { return value instanceof String text && !text.isBlank() && text.length() <= 512; }
}
