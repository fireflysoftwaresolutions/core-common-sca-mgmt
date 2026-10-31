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

import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeSelfRecoveryPolicyTest {
    private final Instant now = Instant.parse("2026-10-31T23:00:00Z");
    private final String issuer = "https://id.example/realms/customer";
    private final List<String> audiences = List.of("firefly-recovery-core", "firefly-recovery-domain");
    private final NativeSelfRecoveryPolicy policy = new NativeSelfRecoveryPolicy(issuer, "customer", List.of("recovery-client"),
            List.of("HUMAN"), audiences, Clock.fixed(now, ZoneOffset.UTC));
    private Map<String, Object> claims() {
        var values = new HashMap<String, Object>();
        values.put("principal_kind", "HUMAN"); values.put("client_id", "recovery-client"); values.put("tenant_id", "tenant");
        values.put("recovery_only", true); values.put("recovery_purpose", "PERMANENT_AUTHENTICATION_UNLOCK");
        values.put("scope", "firefly.recovery"); values.put("typ", "firefly-restricted-recovery"); values.put("recovery_realm", "customer");
        values.put("recovery_lock_generation", "generation"); values.put("recovery_ceremony_id", "ceremony"); values.put("jti", "ceremony");
        values.put("aud", audiences); values.put("iat", now.minusSeconds(1)); values.put("exp", now.plusSeconds(120));
        values.put("party_id", UUID.randomUUID().toString()); return values;
    }
    private SecurityPrincipal actor(Map<String, Object> claims) {
        return SecurityPrincipal.builder().issuer(issuer).subject("subject").tenantId("tenant").claims(claims).build();
    }
    private Map<String, Object> context() {
        return new HashMap<>(Map.of("issuer", issuer, "tenantId", "tenant", "actorSubject", "subject", "clientId", "recovery-client",
                "principalKind", "HUMAN", "expectedTargetIssuer", issuer, "expectedTargetRealm", "customer", "expectedTargetSubject", "subject",
                "expectedLockGeneration", "generation", "operationId", UUID.randomUUID().toString()));
    }
    @Test void onlyExactSelfRecoveryActionsAndPurposeCanBePermitted() {
        for (String action : List.of("challenge.issue", "challenge.verify", "verification.reserve", "verification.complete",
                "authorization.consume", "authorization.verify", "execution.record")) {
            var decision = policy.authorize(actor(claims()), "sca.recovery." + action, "sca.recovery:PERMANENT_AUTHENTICATION_UNLOCK", context()).block();
            assertTrue(decision.granted()); assertTrue(decision.obligations().isEmpty());
        }
        var create = context(); create.put("operationId", "new");
        assertTrue(policy.authorize(actor(claims()), "sca.recovery.create", "sca.recovery:PERMANENT_AUTHENTICATION_UNLOCK", create).block().granted());
        for (String action : List.of("payment.execute", "user.enable", "sca.recovery.admin", "sca.recovery.authorization.consume.extra")) {
            assertFalse(policy.authorize(actor(claims()), action, "sca.recovery:PERMANENT_AUTHENTICATION_UNLOCK", context()).block().granted());
        }
    }
    @Test void machineOrdinaryTokenCrossSubjectGenerationAndBusinessAudienceAreRefused() {
        for (Map.Entry<String, Object> change : Map.<String,Object>of("principal_kind", "MACHINE", "recovery_only", false,
                "recovery_lock_generation", "other-generation", "scope", "openid firefly.recovery", "aud", List.of("business-api"),
                "exp", now, "roles", List.of("admin")).entrySet()) {
            var claims = claims(); claims.put(change.getKey(), change.getValue());
            assertFalse(policy.authorize(actor(claims), "sca.recovery.authorization.consume", "sca.recovery:PERMANENT_AUTHENTICATION_UNLOCK", context()).block().granted());
        }
        var foreign = context(); foreign.put("expectedTargetSubject", "another-person");
        assertFalse(policy.authorize(actor(claims()), "sca.recovery.authorization.consume", "sca.recovery:PERMANENT_AUTHENTICATION_UNLOCK", foreign).block().granted());
        assertFalse(policy.authorize(actor(claims()), "sca.recovery.authorization.consume", "financial:grant", context()).block().granted());
    }
}
