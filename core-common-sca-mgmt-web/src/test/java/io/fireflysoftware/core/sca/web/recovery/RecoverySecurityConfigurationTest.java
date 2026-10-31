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
import io.fireflysoftware.core.sca.core.recovery.SCARecoveryService;
import io.fireflysoftware.core.sca.web.config.RecoverySecurityConfiguration;
import io.fireflysoftware.core.sca.web.controllers.recovery.SCARecoveryController;
import org.fireflyframework.security.rs.ResourceServerProperties;
import org.fireflyframework.security.rs.ResourceServerAutoConfiguration;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoverySecurityConfigurationTest {
    @Test
    void defaultOffBootsWithoutDecoderTrustGuardOrRecoveryService() {
        new ReactiveWebApplicationContextRunner().withUserConfiguration(RecoverySecurityConfiguration.class, OffControllerConfiguration.class)
                .withPropertyValues("firefly.sca.recovery.enabled=false", "firefly.security.resource-server.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SCARecoveryController.class)
                            .doesNotHaveBean(SCARecoveryService.class).doesNotHaveBean(RecoveryWebAuthorization.class)
                            .doesNotHaveBean(ReactiveJwtDecoder.class);
                });
    }

    @Test
    void disabledChainDeniesRecoveryAndEveryLegacyRouteBeforeDispatch() {
        var chain = new RecoverySecurityConfiguration().recoveryDisabledSecurityWebFilterChain(ServerHttpSecurity.http());
        var probe = new RouteProbe();
        var client = WebTestClient.bindToController(probe).webFilter(new WebFilterChainProxy(chain)).build();
        for (String path : List.of("/api/v1/sca/recovery-operations", "/api/v1/sca/operations", "/api/v1/sca/challenges", "/actuator/health")) {
            client.post().uri(path).exchange().expectStatus().isForbidden();
            client.post().uri(path).header("Authorization", "Bearer non-secret-fixture")
                    .exchange().expectStatus().isForbidden();
        }
        assertThat(probe.calls).hasValue(0);
    }

    @Test
    void enabledTrustRequiresHttpsExactIssuerAudienceRequiredTenantStrictPolicyAndAsymmetricAlgorithms() {
        for (String invalid : List.of("httpIssuer", "httpJwks", "wrongIssuer", "wrongAudience", "optionalTenant", "tenantPath", "nonStrict", "permit", "algorithm", "disabledDecoder")) {
            ResourceServerProperties properties = configured();
            var environment = new MockEnvironment().withProperty("firefly.security.resource-server.enabled", "true");
            switch (invalid) {
                case "httpIssuer" -> properties.setIssuer("http://identity.example/realms/restricted-recovery");
                case "httpJwks" -> properties.setJwkSetUri("http://identity.example/keys");
                case "wrongIssuer" -> properties.setIssuer("https://untrusted.example");
                case "wrongAudience" -> properties.setAudiences(List.of("another-service"));
                case "optionalTenant" -> properties.setTenantRequired(false);
                case "tenantPath" -> properties.setTenantClaimPaths(List.of("request.tenant"));
                case "nonStrict" -> properties.setStrictPolicy(false);
                case "permit" -> properties.setPermitMatchers(List.of("/**"));
                case "algorithm" -> properties.setSignatureAlgorithms(List.of("HS256"));
                case "disabledDecoder" -> environment.withProperty("firefly.security.resource-server.enabled", "false");
            }
            String expectedIssuer = invalid.equals("httpIssuer") ? properties.getIssuer() : RecoveryWebAuthorizationTest.ISSUER;
            assertThatThrownBy(() -> RecoverySecurityConfiguration.validate(properties, environment,
                    expectedIssuer, "core-recovery")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void enabledChainStillRefusesAllLegacyMethodsAndDoesNotUseBearerAsRecoveryOwnerProof() {
        var properties = configured();
        var framework = new ResourceServerAutoConfiguration();
        var converter = framework.fireflyJwtAuthenticationConverter(
                framework.fireflyPrincipalFactory(framework.fireflyAuthorityMappingPort(properties)), properties);
        var token = Jwt.withTokenValue("non-secret-test-fixture").header("alg", "RS256")
                .issuer(RecoveryWebAuthorizationTest.ISSUER).subject("opaque/actor:one").audience(List.of("core-recovery"))
                .claim("tenant_id", "opaque/tenant:one").claim("client_id", RecoveryWebAuthorizationTest.CLIENT)
                .claim("principal_kind", "HUMAN").expiresAt(Instant.now().plusSeconds(300)).build();
        java.util.List<RecoveryBoundaryAuditPort.Event> events = new java.util.ArrayList<>();
        RecoveryBoundaryAuditPort audit = new RecoveryBoundaryAuditPort() {
            @Override public boolean isQualified() { return true; }
            @Override public Mono<Void> record(Event event) { return Mono.fromRunnable(() -> events.add(event)); }
        };
        var chain = new RecoverySecurityConfiguration().recoveryEnabledSecurityWebFilterChain(ServerHttpSecurity.http(),
                compact -> Mono.just(token), converter, properties,
                new MockEnvironment().withProperty("firefly.security.resource-server.enabled", "true"),
                RecoveryWebAuthorizationTest.ISSUER, "core-recovery", audit);
        var probe = new RouteProbe();
        var client = WebTestClient.bindToController(probe).webFilter(new WebFilterChainProxy(chain)).build();
        for (String path : List.of("/api/v1/sca/operations", "/api/v1/sca/challenges", "/actuator/health")) {
            client.post().uri(path).header("Authorization", "Bearer non-secret-test-fixture")
                    .exchange().expectStatus().isForbidden();
        }
        client.get().uri("/api/v1/sca/recovery-operations").header("Authorization", "Bearer non-secret-test-fixture")
                .exchange().expectStatus().isForbidden();
        client.post().uri("/api/v1/sca/recovery-operations").exchange().expectStatus().isUnauthorized();
        assertThat(events).hasSize(5);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.actor()).isNull();
            assertThat(event.outcome()).isEqualTo(RecoveryBoundaryAuditPort.Outcome.REFUSED);
        });
        assertThat(events.getLast().action()).isEqualTo(io.fireflysoftware.core.sca.interfaces.recovery.RecoveryAction.CREATE);
        assertThat(probe.calls).hasValue(0);
    }

    @Test
    void recoveryChainPreservesOnlyDecoderValidatedJwtWithGenuineJaflyPrincipal() {
        var properties = configured();
        var framework = new ResourceServerAutoConfiguration();
        var converter = framework.fireflyJwtAuthenticationConverter(
                framework.fireflyPrincipalFactory(framework.fireflyAuthorityMappingPort(properties)), properties);
        var jwt = Jwt.withTokenValue("restricted-synthetic-assertion").header("alg", "RS256")
                .issuer(RecoveryWebAuthorizationTest.ISSUER).subject("opaque/actor:one").audience(List.of("core-recovery"))
                .claim("tenant_id", "opaque/tenant:one").claim("client_id", RecoveryWebAuthorizationTest.CLIENT)
                .claim("principal_kind", "HUMAN").expiresAt(Instant.now().plusSeconds(60)).build();
        var chain = new RecoverySecurityConfiguration().recoveryEnabledSecurityWebFilterChain(ServerHttpSecurity.http(),
                compact -> "restricted-synthetic-assertion".equals(compact) ? Mono.just(jwt) : Mono.error(new IllegalStateException("invalid")),
                converter, properties, new MockEnvironment().withProperty("firefly.security.resource-server.enabled", "true"),
                RecoveryWebAuthorizationTest.ISSUER, "core-recovery", RecoveryAuditFixture.qualified());
        var probe = new CredentialProbe();
        var client = WebTestClient.bindToController(probe).webFilter(new WebFilterChainProxy(chain)).build();
        client.post().uri("/api/v1/sca/recovery-operations").header("Authorization", "Bearer restricted-synthetic-assertion")
                .exchange().expectStatus().isOk();
        assertThat(probe.authentication.get()).isInstanceOf(RecoveryAuthenticationToken.class);
        var authentication = (RecoveryAuthenticationToken) probe.authentication.get();
        assertThat(authentication.getCredentials()).isSameAs(jwt);
        assertThat(authentication.securityPrincipal().tenantId()).isEqualTo("opaque/tenant:one");
        assertThat(authentication.toString()).doesNotContain("restricted-synthetic-assertion", "opaque/actor:one");
        authentication.eraseCredentials();
        assertThat(authentication.getCredentials()).isNull();
    }

    @RestController
    static class CredentialProbe {
        final java.util.concurrent.atomic.AtomicReference<org.springframework.security.core.Authentication> authentication = new java.util.concurrent.atomic.AtomicReference<>();
        @org.springframework.web.bind.annotation.PostMapping("/api/v1/sca/recovery-operations")
        Mono<String> receive() {
            return org.springframework.security.core.context.ReactiveSecurityContextHolder.getContext().map(context -> {
                authentication.set(context.getAuthentication());
                return "verified";
            });
        }
    }

    static ResourceServerProperties configured() {
        var properties = new ResourceServerProperties();
        properties.setIssuer(RecoveryWebAuthorizationTest.ISSUER);
        properties.setAudiences(List.of("core-recovery"));
        properties.setJwkSetUri("https://identity.example/realms/restricted-recovery/protocol/openid-connect/certs");
        properties.setTenantClaimPaths(List.of("tenant_id")); properties.setTenantRequired(true); properties.setStrictPolicy(true);
        return properties;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    @EnableConfigurationProperties(RecoveryProperties.class)
    static class OffControllerConfiguration {
        @Bean
        SCARecoveryController recoveryController(ObjectProvider<SCARecoveryService> services,
                ObjectProvider<RecoveryWebAuthorization> authorizations, RecoveryProperties properties, ObjectProvider<RecoveryBoundaryAuditPort> audits) {
            return new SCARecoveryController(services, authorizations, properties, audits);
        }
    }

    @RestController
    static class RouteProbe {
        final AtomicInteger calls = new AtomicInteger();
        @RequestMapping({"/api/v1/sca/recovery-operations", "/api/v1/sca/operations", "/api/v1/sca/challenges", "/actuator/health"})
        String receive() { calls.incrementAndGet(); return "unavailable"; }
    }
}
