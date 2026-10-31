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


package io.fireflysoftware.core.sca.web.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Original producer metadata only; this test does not enable the runtime recovery feature. */
@ActiveProfiles("openapi-gen")
@SpringBootTest(classes = OpenApiGenApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=openapi-contract-fixture", "spring.main.web-application-type=reactive",
                "server.address=127.0.0.1", "springdoc.api-docs.enabled=true", "springdoc.paths-to-match=/api/**"})
class RecoveryOpenApiContractTest {
    @Value("${local.server.port}")
    private int port;

    @Test
    void originalProducerExposesEightBearerOperationsActualStatusesAndOpaqueNativeContracts() {
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build()
                .get().uri("/v3/api-docs").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.components.securitySchemes.bearerAuth.type").isEqualTo("http")
                .jsonPath("$.components.securitySchemes.bearerAuth.scheme").isEqualTo("bearer")
                .jsonPath("$.paths['/api/v1/sca/recovery-operations'].post.operationId").isEqualTo("createRecoveryOperation")
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/challenges'].post.operationId").isEqualTo("issueRecoveryChallenge")
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/verifications'].post.operationId").isEqualTo("verifyRecoveryChallenge")
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/authorizations'].post.operationId").isEqualTo("consumeRecoveryAuthorization")
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/executions'].post.operationId").isEqualTo("recordRecoveryExecution")
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/verifications/reservations'].post.operationId").isEqualTo("reserveRecoveryVerification")
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/verifications/complete'].post.operationId").isEqualTo("completeRecoveryVerification")
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/authorizations/verify'].post.operationId").isEqualTo("verifyRecoveryAuthorization")
                .jsonPath("$.paths['/api/v1/sca/recovery-operations'].post.responses['201']").exists()
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/challenges'].post.responses['201']").exists()
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/verifications'].post.responses['202']").exists()
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/executions'].post.responses['204']").exists()
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/verifications/reservations'].post.responses['201']").exists()
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/verifications/complete'].post.responses['202']").exists()
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/authorizations/verify'].post.responses['200']").exists()
                .jsonPath("$.paths['/api/v1/sca/recovery-operations'].post.security[0].bearerAuth").isArray()
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/verifications'].post.security[0].bearerAuth").isArray()
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/verifications/reservations'].post.security[0].bearerAuth").isArray()
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/verifications/complete'].post.security[0].bearerAuth").isArray()
                .jsonPath("$.paths['/api/v1/sca/recovery-operations/{operationId}/authorizations/verify'].post.security[0].bearerAuth").isArray()
                .jsonPath("$.components.schemas.RecoveryVerificationReservation.properties.reservationId.format").isEqualTo("uuid")
                .jsonPath("$.components.schemas.RecoveryVerificationReservation.properties.providerChallengeId").exists()
                .jsonPath("$.components.schemas.RecoveryVerificationReservation.properties.category").exists()
                .jsonPath("$.components.schemas.CompleteRecoveryVerificationRequest.properties.attestation").exists()
                .jsonPath("$.components.schemas.CompleteRecoveryVerificationRequest.properties.password").doesNotExist()
                .jsonPath("$.components.schemas.CompleteRecoveryVerificationRequest.properties.success").doesNotExist()
                .jsonPath("$.components.schemas.RecoveryVerificationResponse.properties.operationId").exists()
                .jsonPath("$.components.schemas.RecoveryVerificationResponse.properties.status").exists()
                .jsonPath("$.components.schemas.RecoveryVerificationResponse.properties.accepted").doesNotExist()
                .jsonPath("$.components.schemas.RecoveryVerificationResponse.properties.attemptCount").doesNotExist()
                .jsonPath("$.components.schemas.RecoveryVerificationResponse.properties.remainingAttempts").doesNotExist();
    }
}
