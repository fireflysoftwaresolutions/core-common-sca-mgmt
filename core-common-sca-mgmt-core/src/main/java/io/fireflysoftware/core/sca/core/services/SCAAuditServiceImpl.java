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
import org.fireflyframework.core.queries.PaginationUtils;
import io.fireflysoftware.core.sca.core.mappers.SCAAuditMapper;
import io.fireflysoftware.core.sca.interfaces.dtos.SCAAuditDTO;
import io.fireflysoftware.core.sca.models.entities.SCAAudit;
import io.fireflysoftware.core.sca.models.repositories.SCAAuditRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.util.UUID;

@Service
@Transactional
public class SCAAuditServiceImpl implements SCAAuditService {

    @Autowired
    private SCAAuditRepository repository;

    @Autowired
    private SCAAuditMapper mapper;

    @Override
    public Mono<PaginationResponse<SCAAuditDTO>> findAllByOperationId(UUID operationId, PaginationRequest paginationRequest) {
        return PaginationUtils.paginateQuery(
                paginationRequest,
                mapper::toDTO,
                pageable -> repository.findAllByScaOperationId(operationId, pageable),
                () -> repository.countByScaOperationId(operationId)
        );
    }

    @Override
    public Mono<SCAAuditDTO> create(UUID operationId, SCAAuditDTO dto) {
        SCAAudit entity = mapper.toEntity(dto);
        entity.setScaOperationId(operationId);
        return repository.save(entity).map(mapper::toDTO);
    }

    @Override
    public Mono<SCAAuditDTO> findById(UUID operationId, UUID auditId) {
        return repository.findById(auditId)
                .filter(audit -> audit.getScaOperationId().equals(operationId))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<SCAAuditDTO> update(UUID operationId, UUID auditId, SCAAuditDTO dto) {
        return repository.findById(auditId)
                .filter(audit -> audit.getScaOperationId().equals(operationId))
                .flatMap(existingAudit -> {
                    SCAAudit updatedEntity = mapper.toEntity(dto);
                    updatedEntity.setId(auditId);
                    updatedEntity.setScaOperationId(operationId);
                    return repository.save(updatedEntity);
                }).map(mapper::toDTO);
    }

    @Override
    public Mono<Void> delete(UUID operationId, UUID auditId) {
        return repository.findById(auditId)
                .filter(audit -> audit.getScaOperationId().equals(operationId))
                .flatMap(repository::delete);
    }
}
