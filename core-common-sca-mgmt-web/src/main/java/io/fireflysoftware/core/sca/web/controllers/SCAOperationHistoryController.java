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
import io.fireflysoftware.core.sca.core.services.SCAOperationHistoryService;
import io.fireflysoftware.core.sca.interfaces.dtos.SCAOperationHistoryDTO;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Controller for managing historical status changes/events for a given SCA Operation.
 */
@RestController
@RequestMapping("/api/v1/sca/operations/{operationId}/history")
public class SCAOperationHistoryController {

    @Autowired
    private SCAOperationHistoryService historyService;


    @GetMapping
    public Mono<ResponseEntity<PaginationResponse<SCAOperationHistoryDTO>>> getAllHistory(
            @PathVariable UUID operationId,
            @ParameterObject @ModelAttribute PaginationRequest paginationRequest
    ) {
        return historyService.findAllByOperationId(operationId, paginationRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Mono<ResponseEntity<SCAOperationHistoryDTO>> createHistory(
            @PathVariable UUID operationId,
            @RequestBody SCAOperationHistoryDTO dto
    ) {
        return historyService.create(operationId, dto)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{historyId}")
    public Mono<ResponseEntity<SCAOperationHistoryDTO>> getHistory(
            @PathVariable UUID operationId,
            @PathVariable UUID historyId
    ) {
        return historyService.findById(operationId, historyId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/{historyId}")
    public Mono<ResponseEntity<SCAOperationHistoryDTO>> updateHistory(
            @PathVariable UUID operationId,
            @PathVariable UUID historyId,
            @RequestBody SCAOperationHistoryDTO dto
    ) {
        return historyService.update(operationId, historyId, dto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{historyId}")
    public Mono<ResponseEntity<Void>> deleteHistory(
            @PathVariable UUID operationId,
            @PathVariable UUID historyId
    ) {
        return historyService.delete(operationId, historyId)
                .then(Mono.fromCallable(() -> ResponseEntity.noContent().<Void>build()))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}