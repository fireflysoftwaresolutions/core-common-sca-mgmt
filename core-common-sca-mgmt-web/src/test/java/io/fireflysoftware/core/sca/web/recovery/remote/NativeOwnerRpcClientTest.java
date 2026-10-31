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

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.fireflysoftware.core.sca.core.recovery.*;
import io.fireflysoftware.core.sca.interfaces.recovery.*;
import io.fireflysoftware.core.sca.web.recovery.RecoveryAuthenticationToken;
import io.netty.handler.ssl.SslContextBuilder;
import org.fireflyframework.security.rs.ResourceServerAutoConfiguration;
import org.fireflyframework.security.rs.ResourceServerProperties;
import org.fireflyframework.security.webflux.authentication.FireflyAuthenticationToken;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTPS transport with a trusted ephemeral fixture CA; never native-provider acceptance. */
class NativeOwnerRpcClientTest {
    @TempDir static Path temporary;
    private static HttpsServer server;
    private static ExecutorService executor;
    private static String issuer;
    private static final AtomicReference<Reply> reply = new AtomicReference<>();
    private static final AtomicInteger requests = new AtomicInteger();
    private static final AtomicReference<String> authorization = new AtomicReference<>();
    private static final AtomicReference<String> body = new AtomicReference<>();
    private NativeOwnerProperties properties;
    private NativeOwnerRpcClient client;
    private RecoveryBinding binding;
    private RecoveryAuthenticationToken authentication;

    @BeforeAll
    static void startOwnedHttpsFixture() throws Exception {
        run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", temporary.resolve("key.pem").toString(),
                "-out", temporary.resolve("certificate.pem").toString(), "-subj", "/CN=localhost", "-days", "1", "-addext", "subjectAltName=DNS:localhost");
        run("openssl", "pkcs12", "-export", "-out", temporary.resolve("fixture.p12").toString(), "-inkey", temporary.resolve("key.pem").toString(),
                "-in", temporary.resolve("certificate.pem").toString(), "-passout", "pass:synthetic-fixture-password");
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(temporary.resolve("fixture.p12"))) { keys.load(input, "synthetic-fixture-password".toCharArray()); }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "synthetic-fixture-password".toCharArray());
        var ssl = SSLContext.getInstance("TLS"); ssl.init(managers.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/realms/customer/firefly-recovery/rpc", exchange -> {
            requests.incrementAndGet(); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readNBytes(65537), java.nio.charset.StandardCharsets.UTF_8));
            Reply selected = reply.get();
            try {
                if (selected.delayMillis() > 0) Thread.sleep(selected.delayMillis());
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (selected.location() != null) exchange.getResponseHeaders().set("Location", selected.location());
                byte[] response = selected.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(selected.status(), response.length); exchange.getResponseBody().write(response);
            } catch (Exception ignored) {
                // Cancellation/timeout closes only this synthetic request.
            } finally { exchange.close(); }
        });
        server.start(); issuer = "https://localhost:" + server.getAddress().getPort() + "/realms/customer";
    }

    @AfterAll static void stopOwnedFixture() { if (server != null) server.stop(0); if (executor != null) executor.shutdownNow(); }

    @BeforeEach
    void configure() throws Exception {
        reply.set(new Reply(200, "{}", null, 0)); requests.set(0); authorization.set(null); body.set(null);
        Path secret = temporary.resolve("core-workload-secret"); Files.writeString(secret, "synthetic-workload-secret");
        properties = new NativeOwnerProperties(); properties.setBaseUrl(URI.create(issuer + "/firefly-recovery/rpc"));
        properties.setProviderId("firefly-keycloak-native-v1"); properties.setWorkloadClientId("core-native-rpc"); properties.setWorkloadSecretFile(secret);
        properties.setTimeout(Duration.ofSeconds(2)); properties.setMaxResponseBytes(4096);
        client = trustedClient(properties);
        binding = new RecoveryBinding(issuer, "tenant", "recovery-client", null, "subject", issuer, "customer", "subject",
                "HUMAN", "PERMANENT_AUTHENTICATION_UNLOCK", "generation");
        var jwt = Jwt.withTokenValue("synthetic-restricted-actor-assertion").header("alg", "RS256")
                .issuer(issuer).subject("subject").audience(List.of("core-recovery"))
                .claim("typ", "firefly-restricted-recovery").claim("recovery_only", true)
                .claim("recovery_purpose", binding.purpose()).claim("scope", "firefly.recovery")
                .claim("tenant_id", "tenant").claim("client_id", "recovery-client").claim("principal_kind", "HUMAN")
                .claim("recovery_lock_generation", "generation").claim("recovery_realm", "customer")
                .expiresAt(Instant.now().plusSeconds(60)).build();
        var framework = new ResourceServerAutoConfiguration(); var receiver = new ResourceServerProperties();
        receiver.setIssuer(issuer); receiver.setAudiences(List.of("core-recovery")); receiver.setTenantClaimPaths(List.of("tenant_id")); receiver.setTenantRequired(true);
        var projected = framework.fireflyJwtAuthenticationConverter(framework.fireflyPrincipalFactory(framework.fireflyAuthorityMappingPort(receiver)), receiver)
                .convert(jwt).block();
        authentication = new RecoveryAuthenticationToken((FireflyAuthenticationToken) projected, jwt);
    }

    @Test
    void httpsRpcUsesDistinctWorkloadBasicAndOnlyVerifiedContextActorAssertion() {
        assertEquals("{}", request(client).contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block(Duration.ofSeconds(5)));
        assertEquals("Basic " + Base64.getEncoder().encodeToString("core-native-rpc:synthetic-workload-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)), authorization.get());
        assertTrue(body.get().contains("synthetic-restricted-actor-assertion"));
        assertFalse(body.get().contains("synthetic-workload-secret"));
        assertThrows(RecoveryDeniedException.class, () -> request(client).block(Duration.ofSeconds(5)));
        assertEquals(1, requests.get());
    }

    @Test
    void redirectOversizedBodyAndTimeoutFailClosedWithoutCredentialBearingErrors() throws Exception {
        reply.set(new Reply(302, "{}", issuer + "/firefly-recovery/rpc/admit", 0));
        assertUnavailable(); assertEquals(1, requests.get());
        reply.set(new Reply(200, "x".repeat(8192), null, 0)); assertUnavailable();
        properties.setTimeout(Duration.ofMillis(200)); client = trustedClient(properties);
        reply.set(new Reply(200, "{}", null, 1000)); assertUnavailable();
    }

    @Test
    void untrustedCertificateAndHttpConfigurationAreRefused() {
        assertThrows(RecoveryUnavailableException.class, () -> request(new NativeOwnerRpcClient(properties, issuer, "core-recovery"))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block(Duration.ofSeconds(5)));
        properties.setBaseUrl(URI.create(issuer.replace("https:", "http:") + "/firefly-recovery/rpc"));
        assertThrows(IllegalStateException.class, () -> new NativeOwnerRpcClient(properties, issuer, "core-recovery"));
    }

    @Test
    void nativeAdmissionRequiresExactCanonicalBindingAndCurrentAction() {
        var adapter = new RemoteNativeRecoveryAdapter(client, properties);
        reply.set(new Reply(200, "{\"binding\":" + bindingJson() + ",\"ownerLinkId\":\"owner-link\",\"identityLinkAttestationId\":\"link-attestation\",\"lockAttestationId\":\"generation\",\"validUntil\":\"" + Instant.now().plusSeconds(30) + "\",\"action\":\"RESERVE_VERIFICATION\"}", null, 0));
        var context = adapter.admit(binding, RecoveryAction.RESERVE_VERIFICATION)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block(Duration.ofSeconds(5));
        assertNotNull(context); assertEquals(binding, context.binding());
        reply.set(new Reply(200, "{\"binding\":" + bindingJson().replace("\"tenant\":\"tenant\"", "\"tenant\":\"foreign-tenant\"") + ",\"ownerLinkId\":\"owner-link\",\"identityLinkAttestationId\":\"link-attestation\",\"lockAttestationId\":\"generation\",\"validUntil\":\"" + Instant.now().plusSeconds(30) + "\",\"action\":\"RESERVE_VERIFICATION\"}", null, 0));
        assertThrows(RecoveryDeniedException.class, () -> adapter.admit(binding, RecoveryAction.RESERVE_VERIFICATION)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block(Duration.ofSeconds(5)));
    }

    @Test
    void rawPossessionResponseNeverCrossesTheRemoteAdapter() {
        var adapter = new RemoteNativeRecoveryAdapter(client, properties);
        var context = new TrustedRecoveryContext(binding, "link", "identity", "lock", Instant.now().plusSeconds(30), RecoveryAction.COMPLETE_VERIFICATION);
        var reference = new RecoveryChallengeReference(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), binding,
                RecoveryFactorCategory.POSSESSION, properties.getProviderId(), "enrollment", "independent-origin", "ceremony", Instant.now().plusSeconds(30));
        assertThrows(RecoveryDeniedException.class, () -> adapter.verify(context, reference, RecoveryProofKind.POSSESSION_RESPONSE, "123456")
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block(Duration.ofSeconds(5)));
        assertEquals(0, requests.get());
    }

    private Mono<String> request(NativeOwnerRpcClient selected) {
        return selected.call("admit", new NativeOwnerRpc(null, binding, null, null, null, null, null, null, null, null, RecoveryAction.CREATE), String.class);
    }
    private void assertUnavailable() {
        var error = assertThrows(RecoveryUnavailableException.class, () -> request(client)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block(Duration.ofSeconds(5)));
        assertNull(error.getCause()); assertFalse(error.toString().contains("synthetic-"));
    }
    private static NativeOwnerRpcClient trustedClient(NativeOwnerProperties settings) throws Exception {
        var tls = SslContextBuilder.forClient().trustManager(temporary.resolve("certificate.pem").toFile()).build();
        var connector = new ReactorClientHttpConnector(HttpClient.create().followRedirect(false).responseTimeout(settings.getTimeout())
                .secure(ssl -> ssl.sslContext(tls).handlerConfigurator(handler -> {
                    var parameters = handler.engine().getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); handler.engine().setSSLParameters(parameters);
                })));
        return new NativeOwnerRpcClient(settings, issuer, "core-recovery", WebClient.builder().clientConnector(connector));
    }
    private String bindingJson() {
        return "{\"issuer\":\"" + issuer + "\",\"tenant\":\"tenant\",\"clientId\":\"recovery-client\",\"delegatingClientId\":null,\"actorSubject\":\"subject\",\"targetIssuer\":\"" + issuer
                + "\",\"targetRealm\":\"customer\",\"targetSubject\":\"subject\",\"population\":\"HUMAN\",\"purpose\":\"PERMANENT_AUTHENTICATION_UNLOCK\",\"lockGeneration\":\"generation\"}";
    }
    private static void run(String... command) throws Exception {
        var process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
    }
    private record Reply(int status, String body, String location, int delayMillis) { }
}
