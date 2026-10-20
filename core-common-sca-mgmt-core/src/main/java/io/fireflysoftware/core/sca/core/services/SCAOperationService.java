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


package io.fireflysoftware.core.sca.core.services;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.sca.interfaces.dtos.SCAOperationDTO;
import io.fireflysoftware.core.sca.interfaces.dtos.ValidationResultDTO;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Defines operations for managing the core SCAOperation entity,
 * including creation, update, trigger, and validate flows.
 */
public interface SCAOperationService {

    // --- CRUD Methods ---
    Mono<PaginationResponse<SCAOperationDTO>> filterAll(FilterRequest<SCAOperationDTO> filterRequest);
    Mono<SCAOperationDTO> create(SCAOperationDTO dto);
    Mono<SCAOperationDTO> findById(UUID operationId);
    Mono<SCAOperationDTO> update(UUID operationId, SCAOperationDTO dto);
    Mono<Void> delete(UUID operationId);

    // --- SCA Flow Methods ---
    /**
     * Trigger the SCA operation: generate challenges, send OTP, set status to PENDING, etc.
     */
    Mono<Void> triggerSCA(UUID operationId);

    /**
     * Validate the entire SCA operation, possibly with a user code (OTP).
     * Returns a ValidationResultDTO indicating success/failure/locked.
     */
    Mono<ValidationResultDTO> validateSCA(UUID operationId, String userCode);
}