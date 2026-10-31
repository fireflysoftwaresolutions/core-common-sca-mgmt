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


package io.fireflysoftware.core.sca.web.config;

import org.fireflyframework.security.rs.ResourceServerProperties;
import io.fireflysoftware.core.sca.web.recovery.RecoveryBoundaryAuditPort;
import io.fireflysoftware.core.sca.web.recovery.RecoveryAuthenticationToken;
import org.fireflyframework.security.webflux.authentication.FireflyAuthenticationToken;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Set;

/** Recovery stays closed without decoder/trust requirements until explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class RecoverySecurityConfiguration {
    @Bean
    @ConditionalOnMissingBean(RecoveryBoundaryAuditPort.class)
    public RecoveryBoundaryAuditPort unavailableRecoveryBoundaryAudit() {
        return event -> Mono.error(new IllegalStateException("Qualified recovery boundary audit unavailable"));
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.sca.recovery.enabled", havingValue = "false", matchIfMissing = true)
    public SecurityWebFilterChain recoveryDisabledSecurityWebFilterChain(ServerHttpSecurity http) {
        return stateless(http)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.FORBIDDEN))
                        .accessDeniedHandler((exchange, error) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            return exchange.getResponse().setComplete();
                        }))
                .authorizeExchange(exchanges -> exchanges.anyExchange().denyAll()).build();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.sca.recovery.enabled", havingValue = "true")
    public SecurityWebFilterChain recoveryEnabledSecurityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder decoder,
            Converter<Jwt, Mono<AbstractAuthenticationToken>> converter, ResourceServerProperties properties,
            Environment environment, @Value("${firefly.sca.recovery.receiver.issuer}") String issuer,
            @Value("${firefly.sca.recovery.receiver.audience}") String audience, RecoveryBoundaryAuditPort audit) {
        validate(properties, environment, issuer, audience);
        if (audit == null || !audit.isQualified()) throw new IllegalStateException("Qualified durable recovery boundary audit required");
        ServerAuthenticationEntryPoint entryPoint = (exchange, error) -> auditRefusal(audit, exchange)
                .then(Mono.defer(() -> new BearerTokenServerAuthenticationEntryPoint().commence(exchange,
                        new BadCredentialsException("Verified recovery caller required"))))
                .onErrorResume(auditFailure -> auditUnavailable(exchange));
        ServerAccessDeniedHandler deniedHandler = (exchange, error) -> auditRefusal(audit, exchange)
                .then(Mono.defer(() -> new BearerTokenServerAccessDeniedHandler().handle(exchange,
                        new AccessDeniedException("Recovery route refused"))))
                .onErrorResume(auditFailure -> auditUnavailable(exchange));
        String base = "/api/v1/sca/recovery-operations";
        return stateless(http)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .authorizeExchange(exchanges -> exchanges.pathMatchers(HttpMethod.POST, base,
                                base + "/*/challenges", base + "/*/verifications", base + "/*/authorizations", base + "/*/executions",
                                base + "/*/verifications/reservations", base + "/*/verifications/complete", base + "/*/authorizations/verify")
                        .authenticated().anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler)
                        .jwt(jwt -> jwt.jwtDecoder(decoder).jwtAuthenticationConverter(verified ->
                                Mono.defer(() -> converter.convert(verified)).map(converted -> {
                                    if (!(converted instanceof FireflyAuthenticationToken firefly) || !converted.isAuthenticated()) {
                                        throw new BadCredentialsException("Verified recovery principal required");
                                    }
                                    return new RecoveryAuthenticationToken(firefly, verified);
                                })))).build();
    }

    public static void validate(ResourceServerProperties properties, Environment environment, String issuer, String audience) {
        Set<String> asymmetric = Set.of("RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512");
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                || !environment.getProperty("firefly.security.resource-server.enabled", Boolean.class, false)
                || !issuer.equals(properties.getIssuer()) || !List.of(audience).equals(properties.getAudiences())
                || properties.getJwkSetUri() == null || properties.getJwkSetUri().isBlank()
                || !properties.isTenantRequired() || !List.of("tenant_id").equals(properties.getTenantClaimPaths())
                || !properties.isStrictPolicy() || !properties.getPermitMatchers().isEmpty()
                || properties.getSignatureAlgorithms().isEmpty() || !asymmetric.containsAll(properties.getSignatureAlgorithms())) {
            throw new IllegalStateException("Explicit trusted recovery resource-server and strict policy configuration required");
        }
        try {
            for (String value : List.of(issuer, properties.getJwkSetUri())) {
                URI uri = URI.create(value);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getRawQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Recovery issuer and JWKS must use explicit HTTPS URLs without credentials");
        }
    }

    private static Mono<Void> auditRefusal(RecoveryBoundaryAuditPort audit, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        String base = "/api/v1/sca/recovery-operations";
        RecoveryAction action = null;
        if (exchange.getRequest().getMethod() == HttpMethod.POST) {
            if (base.equals(path)) action = RecoveryAction.CREATE;
            else if (path.startsWith(base + "/")) {
                String[] segments = path.substring(base.length() + 1).split("/", -1);
                if (segments.length == 3) {
                    if ("verifications".equals(segments[1]) && "reservations".equals(segments[2])) action = RecoveryAction.RESERVE_VERIFICATION;
                    else if ("verifications".equals(segments[1]) && "complete".equals(segments[2])) action = RecoveryAction.COMPLETE_VERIFICATION;
                    else if ("authorizations".equals(segments[1]) && "verify".equals(segments[2])) action = RecoveryAction.VERIFY_AUTHORIZATION;
                }
                if (segments.length == 2) action = switch (segments[1]) {
                    case "challenges" -> RecoveryAction.ISSUE_CHALLENGE;
                    case "verifications" -> RecoveryAction.VERIFY_CHALLENGE;
                    case "authorizations" -> RecoveryAction.CONSUME;
                    case "executions" -> RecoveryAction.RECORD_EXECUTION;
                    default -> null;
                };
            }
        }
        var event = new RecoveryBoundaryAuditPort.Event(null, action, RecoveryBoundaryAuditPort.Outcome.REFUSED);
        return Mono.defer(() -> audit.record(event)).timeout(Duration.ofSeconds(5));
    }

    private static Mono<Void> auditUnavailable(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return exchange.getResponse().setComplete();
    }

    private static ServerHttpSecurity stateless(ServerHttpSecurity http) {
        return http.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .csrf(ServerHttpSecurity.CsrfSpec::disable).httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable).logout(ServerHttpSecurity.LogoutSpec::disable);
    }
}
