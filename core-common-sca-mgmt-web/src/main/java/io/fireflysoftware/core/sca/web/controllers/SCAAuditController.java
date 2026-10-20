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
import io.fireflysoftware.core.sca.core.services.SCAAuditService;
import io.fireflysoftware.core.sca.interfaces.dtos.SCAAuditDTO;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Controller for managing audit records for an SCA Operation.
 * Optionally, you can also have a challenge-level audit path if needed.
 */
@RestController
@RequestMapping("/api/sca/v1/operations/{operationId}/audit")
public class SCAAuditController {

    @Autowired
    private SCAAuditService auditService;

    @GetMapping
    public Mono<ResponseEntity<PaginationResponse<SCAAuditDTO>>> getAllAudit(
            @PathVariable UUID operationId,
            @ParameterObject @ModelAttribute PaginationRequest paginationRequest
    ) {
        return auditService.findAllByOperationId(operationId, paginationRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Mono<ResponseEntity<SCAAuditDTO>> createAuditEvent(
            @PathVariable UUID operationId,
            @RequestBody SCAAuditDTO dto
    ) {
        return auditService.create(operationId, dto)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{auditId}")
    public Mono<ResponseEntity<SCAAuditDTO>> getAuditEvent(
            @PathVariable UUID operationId,
            @PathVariable UUID auditId
    ) {
        return auditService.findById(operationId, auditId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/{auditId}")
    public Mono<ResponseEntity<SCAAuditDTO>> updateAuditEvent(
            @PathVariable UUID operationId,
            @PathVariable UUID auditId,
            @RequestBody SCAAuditDTO dto
    ) {
        return auditService.update(operationId, auditId, dto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{auditId}")
    public Mono<ResponseEntity<Void>> deleteAuditEvent(
            @PathVariable UUID operationId,
            @PathVariable UUID auditId
    ) {
        return auditService.delete(operationId, auditId)
                .then(Mono.fromCallable(() -> ResponseEntity.noContent().<Void>build()))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}