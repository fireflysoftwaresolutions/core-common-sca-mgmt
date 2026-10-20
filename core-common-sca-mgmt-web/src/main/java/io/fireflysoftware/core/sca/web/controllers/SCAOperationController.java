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

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.sca.core.services.SCAOperationService;
import io.fireflysoftware.core.sca.interfaces.dtos.SCAOperationDTO;
import io.fireflysoftware.core.sca.interfaces.dtos.ValidationResultDTO;
import jakarta.validation.Validation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Controller responsible for managing SCA Operations (core entity).
 * Delegates to SCAOperationService for business logic.
 */
@RestController
@RequestMapping("/api/v1/sca/operations")
public class SCAOperationController {

    @Autowired
    private SCAOperationService operationService;

    // --- CRUD Endpoints ---

    @GetMapping
    public Mono<ResponseEntity<PaginationResponse<SCAOperationDTO>>> filterOperations(FilterRequest<SCAOperationDTO> filterRequest) {
        return operationService.filterAll(filterRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Mono<ResponseEntity<SCAOperationDTO>> createOperation(@RequestBody SCAOperationDTO dto) {
        return operationService.create(dto)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{operationId}")
    public Mono<ResponseEntity<SCAOperationDTO>> getOperation(@PathVariable UUID operationId) {
        return operationService.findById(operationId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/{operationId}")
    public Mono<ResponseEntity<SCAOperationDTO>> updateOperation(
            @PathVariable UUID operationId,
            @RequestBody SCAOperationDTO dto
    ) {
        return operationService.update(operationId, dto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{operationId}")
    public Mono<ResponseEntity<Void>> deleteOperation(@PathVariable UUID operationId) {
        return operationService.delete(operationId)
                .map(r -> ResponseEntity.noContent().<Void>build())
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // --- SCA Flow Endpoints ---

    @PostMapping("/{operationId}/trigger")
    public Mono<ResponseEntity<Void>> triggerSCA(@PathVariable UUID operationId) {
        return operationService.triggerSCA(operationId)
                .map(r -> ResponseEntity.ok().<Void>build())
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping("/{operationId}/validate")
    public Mono<ResponseEntity<ValidationResultDTO>> validateSCA(
            @PathVariable UUID operationId,
            @RequestParam(name="userCode", required=false) String userCode
    ) {
        return operationService.validateSCA(operationId, userCode)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}

