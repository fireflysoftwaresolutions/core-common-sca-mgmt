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
import io.fireflysoftware.core.sca.core.mappers.SCAOperationHistoryMapper;
import io.fireflysoftware.core.sca.interfaces.dtos.SCAOperationHistoryDTO;
import io.fireflysoftware.core.sca.models.entities.SCAOperationHistory;
import io.fireflysoftware.core.sca.models.repositories.SCAOperationHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.util.UUID;

@Service
@Transactional
public class SCAOperationHistoryServiceImpl implements SCAOperationHistoryService {

    @Autowired
    private SCAOperationHistoryRepository repository;

    @Autowired
    private SCAOperationHistoryMapper mapper;

    @Override
    public Mono<PaginationResponse<SCAOperationHistoryDTO>> findAllByOperationId(UUID operationId, PaginationRequest paginationRequest) {
        return PaginationUtils.paginateQuery(
                paginationRequest,
                mapper::toDTO,
                pageable -> repository.findAllByScaOperationId(operationId, pageable),
                () -> repository.countByScaOperationId(operationId)
        );
    }

    @Override
    public Mono<SCAOperationHistoryDTO> create(UUID operationId, SCAOperationHistoryDTO dto) {
        SCAOperationHistory entity = mapper.toEntity(dto);
        entity.setScaOperationId(operationId);
        return repository.save(entity).map(mapper::toDTO);
    }

    @Override
    public Mono<SCAOperationHistoryDTO> findById(UUID operationId, UUID historyId) {
        return repository.findById(historyId)
                .filter(history -> history.getScaOperationId().equals(operationId))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<SCAOperationHistoryDTO> update(UUID operationId, UUID historyId, SCAOperationHistoryDTO dto) {
        return repository.findById(historyId)
                .filter(history -> history.getScaOperationId().equals(operationId))
                .flatMap(existingHistory -> {
                    SCAOperationHistory updatedEntity = mapper.toEntity(dto);
                    updatedEntity.setId(historyId);
                    updatedEntity.setScaOperationId(operationId);
                    return repository.save(updatedEntity);
                }).map(mapper::toDTO);
    }

    @Override
    public Mono<Void> delete(UUID operationId, UUID historyId) {
        return repository.findById(historyId)
                .filter(history -> history.getScaOperationId().equals(operationId))
                .flatMap(repository::delete);
    }
}