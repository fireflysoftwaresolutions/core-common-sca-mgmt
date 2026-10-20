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
import io.fireflysoftware.core.sca.core.mappers.SCAAttemptMapper;
import io.fireflysoftware.core.sca.interfaces.dtos.SCAAttemptDTO;
import io.fireflysoftware.core.sca.models.entities.SCAAttempt;
import io.fireflysoftware.core.sca.models.repositories.SCAAttemptRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.util.UUID;

@Service
@Transactional
public class SCAAttemptServiceImpl implements SCAAttemptService {

    @Autowired
    private SCAAttemptRepository repository;

    @Autowired
    private SCAAttemptMapper mapper;

    @Override
    public Mono<PaginationResponse<SCAAttemptDTO>> findAllByChallengeId(UUID operationId, UUID challengeId, PaginationRequest paginationRequest) {
        return PaginationUtils.paginateQuery(
                paginationRequest,
                mapper::toDTO,
                pageable -> repository.findAllByScaChallengeId(challengeId, pageable),
                () -> repository.countByScaChallengeId(challengeId)
        );
    }

    @Override
    public Mono<SCAAttemptDTO> create(UUID operationId, UUID challengeId, SCAAttemptDTO dto) {
        dto.setScaChallengeId(challengeId);
        SCAAttempt entity = mapper.toEntity(dto);
        return repository.save(entity)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<SCAAttemptDTO> findById(UUID operationId, UUID challengeId, UUID attemptId) {
        return repository.findById(attemptId)
                .filter(attempt -> challengeId.equals(attempt.getScaChallengeId()))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Attempt not found or does not belong to the challenge.")))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<SCAAttemptDTO> update(UUID operationId, UUID challengeId, UUID attemptId, SCAAttemptDTO dto) {
        return repository.findById(attemptId)
                .filter(attempt -> challengeId.equals(attempt.getScaChallengeId()))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Attempt not found or does not belong to the challenge.")))
                .flatMap(existingAttempt -> {
                    SCAAttempt updatedEntity = mapper.toEntity(dto);
                    updatedEntity.setId(attemptId);
                    updatedEntity.setScaChallengeId(challengeId);
                    return repository.save(updatedEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> delete(UUID operationId, UUID challengeId, UUID attemptId) {
        return repository.findById(attemptId)
                .filter(attempt -> challengeId.equals(attempt.getScaChallengeId()))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Attempt not found or does not belong to the challenge.")))
                .flatMap(repository::delete);
    }
}
