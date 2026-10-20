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

import org.fireflyframework.core.queries.PaginationRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.sca.interfaces.dtos.SCAAuditDTO;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Defines operations for logging or retrieving in-depth audit events
 * associated with an SCA Operation or Challenge.
 */
public interface SCAAuditService {
    Mono<PaginationResponse<SCAAuditDTO>> findAllByOperationId(UUID operationId, PaginationRequest paginationRequest);
    Mono<SCAAuditDTO> create(UUID operationId, SCAAuditDTO dto);
    Mono<SCAAuditDTO> findById(UUID operationId, UUID auditId);
    Mono<SCAAuditDTO> update(UUID operationId, UUID auditId, SCAAuditDTO dto);
    Mono<Void> delete(UUID operationId, UUID auditId);
}