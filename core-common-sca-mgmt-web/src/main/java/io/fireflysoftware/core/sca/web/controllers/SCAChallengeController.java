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
import io.fireflysoftware.core.sca.core.services.SCAChallengeService;
import io.fireflysoftware.core.sca.interfaces.dtos.SCAChallengeDTO;
import io.fireflysoftware.core.sca.interfaces.dtos.ValidationResultDTO;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sca/operations/{operationId}/challenges")
public class SCAChallengeController {

    @Autowired
    private SCAChallengeService challengeService;

    @GetMapping
    public Mono<ResponseEntity<PaginationResponse<SCAChallengeDTO>>> getAllChallenges(
            @PathVariable UUID operationId,
            @ParameterObject @ModelAttribute PaginationRequest paginationRequest
    ) {
        return challengeService.findAllByOperationId(operationId, paginationRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }


    @PostMapping
    public Mono<ResponseEntity<SCAChallengeDTO>> createChallenge(
            @PathVariable UUID operationId,
            @RequestBody SCAChallengeDTO dto
    ) {
        return challengeService.create(operationId, dto)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{challengeId}")
    public Mono<ResponseEntity<SCAChallengeDTO>> getChallenge(
            @PathVariable UUID operationId,
            @PathVariable UUID challengeId
    ) {
        return challengeService.findById(operationId, challengeId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/{challengeId}")
    public Mono<ResponseEntity<SCAChallengeDTO>> updateChallenge(
            @PathVariable UUID operationId,
            @PathVariable UUID challengeId,
            @RequestBody SCAChallengeDTO dto
    ) {
        return challengeService.update(operationId, challengeId, dto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{challengeId}")
    public Mono<ResponseEntity<Void>> deleteChallenge(
            @PathVariable UUID operationId,
            @PathVariable UUID challengeId
    ) {
        return challengeService.delete(operationId, challengeId)
                .map(r -> ResponseEntity.noContent().<Void>build())
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // --- Validate a Single Challenge ---
    @PostMapping("/{challengeId}/validate")
    public Mono<ResponseEntity<String>> validateChallenge(
            @PathVariable UUID operationId,
            @PathVariable UUID challengeId,
            @RequestParam String userCode
    ) {
        return challengeService.validateChallenge(operationId, challengeId, userCode)
                .flatMap((ValidationResultDTO result) -> {
                    if (result.isLockedOrFailed()) {
                        return Mono.just(ResponseEntity.status(423).body("Challenge locked/failed"));
                    } else if (!result.isSuccess()) {
                        return Mono.just(ResponseEntity.status(403).body("Incorrect code"));
                    } else {
                        return Mono.just(ResponseEntity.ok("Challenge Validated"));
                    }
                })
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}