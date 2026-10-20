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


package io.fireflysoftware.core.sca.web.controllers;

import org.fireflyframework.core.queries.PaginationRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.sca.core.services.SCAAttemptService;
import io.fireflysoftware.core.sca.interfaces.dtos.SCAAttemptDTO;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Controller for managing SCA Attempts within a specific challenge (OTP attempts).
 */
@RestController
@RequestMapping("/api/v1/sca/operations/{operationId}/challenges/{challengeId}/attempts")
public class SCAAttemptController {

    @Autowired
    private SCAAttemptService attemptService;

    @GetMapping
    public Mono<ResponseEntity<PaginationResponse<SCAAttemptDTO>>> getAllAttempts(
            @PathVariable UUID operationId,
            @PathVariable UUID challengeId,
            @ParameterObject @ModelAttribute PaginationRequest paginationRequest
            ) {
        return attemptService.findAllByChallengeId(operationId, challengeId, paginationRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Mono<ResponseEntity<SCAAttemptDTO>> createAttempt(
            @PathVariable UUID operationId,
            @PathVariable UUID challengeId,
            @RequestBody SCAAttemptDTO dto
    ) {
        return attemptService.create(operationId, challengeId, dto)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{attemptId}")
    public Mono<ResponseEntity<SCAAttemptDTO>> getAttempt(
            @PathVariable UUID operationId,
            @PathVariable UUID challengeId,
            @PathVariable UUID attemptId
    ) {
        return attemptService.findById(operationId, challengeId, attemptId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/{attemptId}")
    public Mono<ResponseEntity<SCAAttemptDTO>> updateAttempt(
            @PathVariable UUID operationId,
            @PathVariable UUID challengeId,
            @PathVariable UUID attemptId,
            @RequestBody SCAAttemptDTO dto
    ) {
        return attemptService.update(operationId, challengeId, attemptId, dto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{attemptId}")
    public Mono<ResponseEntity<Void>> deleteAttempt(
            @PathVariable UUID operationId,
            @PathVariable UUID challengeId,
            @PathVariable UUID attemptId
    ) {
        return attemptService.delete(operationId, challengeId, attemptId)
                .map(r -> ResponseEntity.noContent().<Void>build())
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}