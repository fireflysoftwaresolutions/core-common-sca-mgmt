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


package io.fireflysoftware.core.sca.web.controllers.recovery;

import io.fireflysoftware.core.sca.core.recovery.RecoveryDeniedException;
import io.fireflysoftware.core.sca.core.recovery.RecoveryProperties;
import io.fireflysoftware.core.sca.core.recovery.RecoveryUnavailableException;
import io.fireflysoftware.core.sca.core.recovery.SCARecoveryService;
import io.fireflysoftware.core.sca.interfaces.recovery.*;
import io.fireflysoftware.core.sca.web.recovery.RecoveryWebAuthorizationImpl;
import io.fireflysoftware.core.sca.web.recovery.RecoveryBoundaryAuditPort;
import java.time.Duration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/** Always visible to the original producer; runtime routes and core capability remain default-off. */
@RestController
@RequestMapping("/api/v1/sca/recovery-operations")
@Tag(name = "SCA Recovery")
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({@ApiResponse(responseCode = "401", description = "Verified recovery caller required"),
        @ApiResponse(responseCode = "403", description = "Caller binding, current policy or recovery proof refused"),
        @ApiResponse(responseCode = "503", description = "Recovery disabled or qualified capability unavailable")})
public class SCARecoveryController {
    private final ObjectProvider<SCARecoveryService> services;
    private final ObjectProvider<RecoveryWebAuthorizationImpl> authorizations;
    private final RecoveryProperties properties;
    private final ObjectProvider<RecoveryBoundaryAuditPort> audits;

    public SCARecoveryController(ObjectProvider<SCARecoveryService> services,
            ObjectProvider<RecoveryWebAuthorizationImpl> authorizations, RecoveryProperties properties, ObjectProvider<RecoveryBoundaryAuditPort> audits) {
        this.services = services;
        this.authorizations = authorizations;
        this.properties = properties;
        this.audits = audits;
    }

    @PostMapping
    @DisableIdempotency
    @ApiResponse(responseCode = "201", description = "Recovery response", content = @Content(schema = @Schema(implementation = RecoveryOperationResponse.class)))
    @Operation(operationId = "createRecoveryOperation", summary = "Create a precisely bound recovery operation",
            description = "Caller binding is comparison data, not trusted owner/native lock proof. Qualified core admission is independently required.")
    public Mono<ResponseEntity<RecoveryOperationResponse>> createRecoveryOperation(@RequestBody CreateRecoveryOperationRequest request) {
        return invoke(request == null ? null : request.binding(), RecoveryAction.CREATE, null, request != null && validKey(request.idempotencyKey()),
                        service -> service.createRecoveryOperation(request))
                .switchIfEmpty(Mono.error(unavailable())).map(result -> ResponseEntity.status(HttpStatus.CREATED).body(result));
    }

    @PostMapping("/{operationId}/challenges")
    @DisableIdempotency
    @ApiResponse(responseCode = "201", description = "Recovery response", content = @Content(schema = @Schema(implementation = RecoveryChallengeResponse.class)))
    @Operation(operationId = "issueRecoveryChallenge", summary = "Issue an eligible enrolled-factor challenge")
    public Mono<ResponseEntity<RecoveryChallengeResponse>> issueRecoveryChallenge(@PathVariable UUID operationId,
            @RequestBody IssueRecoveryChallengeRequest request) {
        return invoke(request == null ? null : request.binding(), RecoveryAction.ISSUE_CHALLENGE, operationId, request != null && request.category() != null,
                        service -> service.issueRecoveryChallenge(operationId, request))
                .switchIfEmpty(Mono.error(unavailable())).map(result -> ResponseEntity.status(HttpStatus.CREATED).body(result));
    }

    @PostMapping("/{operationId}/verifications")
    @DisableIdempotency
    @ApiResponse(responseCode = "202", description = "Recovery response", content = @Content(schema = @Schema(implementation = RecoveryVerificationResponse.class)))
    @Operation(operationId = "verifyRecoveryChallenge", summary = "Submit proof for independent verification",
            description = "Acknowledgement only: no factor verdict, attempt count or remaining budget is disclosed. Submitted knowledge credentials are not accepted.")
    public Mono<ResponseEntity<RecoveryVerificationResponse>> verifyRecoveryChallenge(@PathVariable UUID operationId,
            @RequestBody VerifyRecoveryChallengeRequest request) {
        return invoke(request == null ? null : request.binding(), RecoveryAction.VERIFY_CHALLENGE, operationId,
                        request != null && request.challengeId() != null && request.proofKind() != null
                                && request.response() != null && !request.response().isBlank() && request.response().length() <= 16384,
                        service -> service.verifyRecoveryChallenge(operationId, request))
                .switchIfEmpty(Mono.error(unavailable()))
                .map(acknowledgement -> ResponseEntity.accepted().body(new RecoveryVerificationResponse(operationId, "ACKNOWLEDGED")));
    }

    @PostMapping("/{operationId}/authorizations")
    @DisableIdempotency
    @ApiResponse(responseCode = "200", description = "Recovery response", content = @Content(schema = @Schema(implementation = RecoveryAuthorizationReceipt.class)))
    @Operation(operationId = "consumeRecoveryAuthorization", summary = "Consume one exact recovery authorization",
            description = "An owner-verifiable receipt does not establish native execution or unlock success.")
    public Mono<ResponseEntity<RecoveryAuthorizationReceipt>> consumeRecoveryAuthorization(@PathVariable UUID operationId,
            @RequestBody ConsumeRecoveryAuthorizationRequest request) {
        return invoke(request == null ? null : request.binding(), RecoveryAction.CONSUME, operationId, request != null && validKey(request.idempotencyKey()),
                        service -> service.consumeRecoveryAuthorization(operationId, request))
                .switchIfEmpty(Mono.error(unavailable())).map(ResponseEntity::ok);
    }

    @PostMapping("/{operationId}/executions")
    @DisableIdempotency
    @ApiResponse(responseCode = "204", description = "Recovery response", content = @Content)
    @Operation(operationId = "recordRecoveryExecution", summary = "Reconcile a receipt-bound executor outcome",
            description = "The independent qualified executor verifier must attest the outcome; request assertions do not prove provider success.")
    public Mono<ResponseEntity<Void>> recordRecoveryExecution(@PathVariable UUID operationId,
            @RequestBody RecordRecoveryExecutionRequest request) {
        return invoke(request == null ? null : request.binding(), RecoveryAction.RECORD_EXECUTION, operationId,
                        request != null && request.executionId() != null && request.outcome() != null,
                        service -> service.recordRecoveryExecution(operationId, request))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    @PostMapping("/{operationId}/verifications/reservations")
    @DisableIdempotency
    @ApiResponse(responseCode = "201", description = "Committed verification reservation",
            content = @Content(schema = @Schema(implementation = RecoveryVerificationReservation.class)))
    @Operation(operationId = "reserveRecoveryVerification", summary = "Reserve a native verification before credential use")
    public Mono<ResponseEntity<RecoveryVerificationReservation>> reserveRecoveryVerification(@PathVariable UUID operationId,
            @RequestBody ReserveRecoveryVerificationRequest request) {
        return invoke(request == null ? null : request.binding(), RecoveryAction.RESERVE_VERIFICATION, operationId,
                        request != null && request.challengeId() != null,
                        service -> service.reserveRecoveryVerification(operationId, request))
                .switchIfEmpty(Mono.error(unavailable())).map(result -> ResponseEntity.status(HttpStatus.CREATED).body(result));
    }

    @PostMapping("/{operationId}/verifications/complete")
    @DisableIdempotency
    @ApiResponse(responseCode = "202", description = "Generic completion acknowledgement",
            content = @Content(schema = @Schema(implementation = RecoveryVerificationResponse.class)))
    @Operation(operationId = "completeRecoveryVerification", summary = "Complete the exact reserved verification with opaque native evidence")
    public Mono<ResponseEntity<RecoveryVerificationResponse>> completeRecoveryVerification(@PathVariable UUID operationId,
            @RequestBody CompleteRecoveryVerificationRequest request) {
        return invoke(request == null ? null : request.binding(), RecoveryAction.COMPLETE_VERIFICATION, operationId,
                        request != null && request.challengeId() != null && request.reservationId() != null
                                && request.attestation() != null && !request.attestation().isBlank() && request.attestation().length() <= 16384,
                        service -> service.completeRecoveryVerification(operationId, request))
                .switchIfEmpty(Mono.error(unavailable()))
                .map(result -> ResponseEntity.accepted().body(new RecoveryVerificationResponse(operationId, "ACKNOWLEDGED")));
    }

    @PostMapping("/{operationId}/authorizations/verify")
    @DisableIdempotency
    @ApiResponse(responseCode = "200", description = "Current stored consumed authorization",
            content = @Content(schema = @Schema(implementation = RecoveryAuthorizationReceipt.class)))
    @Operation(operationId = "verifyRecoveryAuthorization", summary = "Verify the stored execution-bound authorization under current admission")
    public Mono<ResponseEntity<RecoveryAuthorizationReceipt>> verifyRecoveryAuthorization(@PathVariable UUID operationId,
            @RequestBody VerifyRecoveryAuthorizationRequest request) {
        return invoke(request == null ? null : request.binding(), RecoveryAction.VERIFY_AUTHORIZATION, operationId,
                        request != null && request.executionId() != null && request.expiresAt() != null,
                        service -> service.verifyRecoveryAuthorization(operationId, request))
                .switchIfEmpty(Mono.error(unavailable())).map(ResponseEntity::ok);
    }

    private <T> Mono<T> invoke(RecoveryBinding binding, RecoveryAction action, UUID operation, boolean validShape,
            Function<SCARecoveryService, Mono<T>> invocation) {
        return Mono.defer(() -> {
            if (!properties.isEnabled()) return Mono.error(unavailable());
            if (!validShape || binding == null) {
                RecoveryBoundaryAuditPort audit = audits.getIfAvailable();
                if (audit == null || !audit.isQualified()) return Mono.error(unavailable());
                return Mono.defer(() -> audit.record(new RecoveryBoundaryAuditPort.Event(null, action, RecoveryBoundaryAuditPort.Outcome.REFUSED)))
                        .timeout(Duration.ofSeconds(5)).then(Mono.<T>error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recovery request")));
            }
            RecoveryWebAuthorizationImpl authorization = authorizations.getIfAvailable();
            if (authorization == null) return Mono.error(unavailable());
            return authorization.authorize(binding, action, operation).then(Mono.defer(() -> {
                SCARecoveryService service = services.getIfAvailable();
                return service == null ? Mono.error(unavailable()) : invocation.apply(service);
            }));
        }).onErrorMap(SCARecoveryController::sanitize);
    }

    private static Throwable sanitize(Throwable error) {
        if (error instanceof RecoveryDeniedException) return new ResponseStatusException(HttpStatus.FORBIDDEN, "Recovery request refused");
        if (error instanceof RecoveryUnavailableException) return unavailable();
        if (error instanceof ResponseStatusException response) {
            int code = response.getStatusCode().value();
            if (code == 401) return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Verified recovery caller required");
            if (code == 403) return new ResponseStatusException(HttpStatus.FORBIDDEN, "Recovery request refused");
            if (code == 400) return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recovery request");
        }
        return unavailable();
    }

    private static boolean validKey(String value) { return value != null && !value.isBlank() && value.length() <= 128; }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recovery unavailable");
    }
}
