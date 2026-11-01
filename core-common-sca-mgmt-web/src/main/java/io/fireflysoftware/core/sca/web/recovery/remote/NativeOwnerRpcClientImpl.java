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

package io.fireflysoftware.core.sca.web.recovery.remote;

import io.fireflysoftware.core.sca.core.recovery.RecoveryDeniedException;
import io.fireflysoftware.core.sca.core.recovery.RecoveryUnavailableException;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryBinding;
import io.fireflysoftware.core.sca.web.recovery.RecoveryAuthenticationToken;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Set;

public final class NativeOwnerRpcClientImpl {
    private final NativeOwnerProperties properties;
    private final String issuer;
    private final String audience;
    private final String workloadSecret;
    private final WebClient http;

    public NativeOwnerRpcClientImpl(NativeOwnerProperties properties, String issuer, String audience) {
        this(properties, issuer, audience, httpBuilder(properties, issuer, audience));
    }

    private static WebClient.Builder httpBuilder(NativeOwnerProperties properties, String issuer, String audience) {
        properties.validate(issuer, audience);
        HttpClient transport = HttpClient.create().followRedirect(false).responseTimeout(properties.getTimeout())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.getTimeout().toMillis()));
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(transport));
    }

    NativeOwnerRpcClientImpl(NativeOwnerProperties properties, String issuer, String audience, WebClient.Builder builder) {
        properties.validate(issuer, audience);
        this.properties = properties;
        this.issuer = issuer;
        this.audience = audience;
        try {
            if (!Files.isRegularFile(properties.getWorkloadSecretFile()) || Files.size(properties.getWorkloadSecretFile()) > 4096) throw new IllegalStateException();
            workloadSecret = Files.readString(properties.getWorkloadSecretFile(), StandardCharsets.UTF_8).strip();
            if (workloadSecret.isBlank() || workloadSecret.length() > 2048 || workloadSecret.chars().anyMatch(Character::isISOControl)) throw new IllegalStateException();
        } catch (Exception error) {
            throw new IllegalStateException("Native workload credential unavailable");
        }
        http = builder.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(properties.getMaxResponseBytes())).build();
    }

    Mono<Jwt> verifiedAssertion(RecoveryBinding expected) {
        return ReactiveSecurityContextHolder.getContext().switchIfEmpty(Mono.error(new RecoveryDeniedException()))
                .map(context -> {
                    if (!(context.getAuthentication() instanceof RecoveryAuthenticationToken authentication)
                            || !authentication.isAuthenticated() || !(authentication.getCredentials() instanceof Jwt jwt)) throw new RecoveryDeniedException();
                    Instant now = Instant.now();
                    if (expected == null || !issuer.equals(expected.issuer()) || !issuer.equals(expected.targetIssuer())
                            || jwt.getTokenValue().length() > 16384
                            || jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString())
                            || !expected.actorSubject().equals(jwt.getSubject()) || !jwt.getAudience().contains(audience)
                            || jwt.getExpiresAt() == null || !now.isBefore(jwt.getExpiresAt())
                            || jwt.getNotBefore() != null && now.isBefore(jwt.getNotBefore())
                            || !"firefly-restricted-recovery".equals(jwt.getClaimAsString("typ"))
                            || !Boolean.TRUE.equals(jwt.getClaims().get("recovery_only"))
                            || !expected.purpose().equals(jwt.getClaimAsString("recovery_purpose"))
                            || !"PERMANENT_AUTHENTICATION_UNLOCK".equals(expected.purpose())
                            || !"firefly.recovery".equals(jwt.getClaimAsString("scope"))
                            || !expected.tenant().equals(jwt.getClaimAsString("tenant_id"))
                            || !expected.clientId().equals(jwt.getClaimAsString("client_id")) || expected.delegatingClientId() != null
                            || !Set.of("HUMAN", "STAFF").contains(expected.population())
                            || !expected.population().equals(jwt.getClaimAsString("principal_kind"))
                            || !expected.lockGeneration().equals(jwt.getClaimAsString("recovery_lock_generation"))
                            || !expected.targetRealm().equals(jwt.getClaimAsString("recovery_realm"))) throw new RecoveryDeniedException();
                    return jwt;
                }).onErrorMap(error -> new RecoveryDeniedException());
    }

    <T> Mono<T> call(String route, NativeOwnerRpc request, Class<T> responseType) {
        return verifiedAssertion(request.binding()).flatMap(assertion -> http.post()
                .uri(properties.getBaseUrl().toString() + "/" + route)
                .headers(headers -> headers.setBasicAuth(properties.getWorkloadClientId(), workloadSecret, StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .bodyValue(request.withAssertion(assertion.getTokenValue()))
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status != 200) return response.releaseBody().then(Mono.error(status == 400 || status == 401 || status == 403
                            ? new RecoveryDeniedException() : new RecoveryUnavailableException()));
                    if (!response.headers().contentType().map(MediaType.APPLICATION_JSON::isCompatibleWith).orElse(false)) {
                        return response.releaseBody().then(Mono.error(new RecoveryUnavailableException()));
                    }
                    return response.bodyToMono(responseType);
                })).timeout(properties.getTimeout()).switchIfEmpty(Mono.error(new RecoveryUnavailableException()))
                .onErrorMap(error -> error instanceof RecoveryDeniedException ? new RecoveryDeniedException() : new RecoveryUnavailableException());
    }
}
