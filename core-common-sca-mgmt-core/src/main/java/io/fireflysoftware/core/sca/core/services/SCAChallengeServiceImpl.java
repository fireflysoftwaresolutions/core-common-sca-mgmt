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
import io.fireflysoftware.core.sca.core.mappers.SCAChallengeMapper;
import io.fireflysoftware.core.sca.interfaces.dtos.SCAChallengeDTO;
import io.fireflysoftware.core.sca.interfaces.dtos.ValidationResultDTO;
import io.fireflysoftware.core.sca.models.entities.SCAChallenge;
import io.fireflysoftware.core.sca.models.repositories.SCAChallengeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Transactional
public class SCAChallengeServiceImpl implements SCAChallengeService {

    @Autowired
    private SCAChallengeRepository repository;

    @Autowired
    private SCAChallengeMapper mapper;

    @Override
    public Mono<PaginationResponse<SCAChallengeDTO>> findAllByOperationId(UUID operationId, PaginationRequest paginationRequest) {
        return PaginationUtils.paginateQuery(
                paginationRequest,
                mapper::toDTO,
                pageable -> repository.findAllByScaOperationId(operationId, pageable),
                () -> repository.countByScaOperationId(operationId)
        );
    }

    @Override
    public Mono<SCAChallengeDTO> create(UUID operationId, SCAChallengeDTO dto) {
        SCAChallenge entity = mapper.toEntity(dto);
        entity.markNew();
        entity.setScaOperationId(operationId);
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(java.time.LocalDateTime.now());
        }
        return repository.save(entity).map(mapper::toDTO);
    }

    @Override
    public Mono<SCAChallengeDTO> findById(UUID operationId, UUID challengeId) {
        return repository.findById(challengeId)
                .filter(challenge -> challenge.getScaOperationId().equals(operationId))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<SCAChallengeDTO> update(UUID operationId, UUID challengeId, SCAChallengeDTO dto) {
        return repository.findById(challengeId)
                .filter(challenge -> challenge.getScaOperationId().equals(operationId))
                .flatMap(existingChallenge -> {
                    SCAChallenge updatedEntity = mapper.toEntity(dto);
                    updatedEntity.setId(challengeId);
                    updatedEntity.setScaOperationId(operationId);
                    return repository.save(updatedEntity);
                }).map(mapper::toDTO);
    }

    @Override
    public Mono<Void> delete(UUID operationId, UUID challengeId) {
        return repository.findById(challengeId)
                .filter(challenge -> challenge.getScaOperationId().equals(operationId))
                .flatMap(repository::delete);
    }

    @Override
    public Mono<SCAChallengeDTO> findActiveChallengeForOperation(UUID operationId) {
        return repository.findActiveChallengeForOperation(operationId)
                .map(mapper::toDTO)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("No active challenge found for operation")));
    }

    @Override
    public Mono<ValidationResultDTO> validateChallenge(UUID operationId, UUID challengeId, String userCode) {
        return repository.findById(challengeId)
                .filter(challenge -> challenge.getScaOperationId().equals(operationId))
                .flatMap(challenge -> {
                    if (Boolean.TRUE.equals(challenge.getUsed())) {
                        return Mono.just(new ValidationResultDTO(false, true, "Challenge already used"));
                    }
                    if (!challenge.getChallengeCode().equals(userCode)) {
                        return Mono.just(new ValidationResultDTO(false, false, "Invalid challenge code"));
                    }
                    if (challenge.getExpiresAt().isBefore(LocalDateTime.now())) {
                        return Mono.just(new ValidationResultDTO(false, true, "Challenge has expired"));
                    }

                    challenge.setUsed(true);
                    return repository.save(challenge)
                            .thenReturn(new ValidationResultDTO(true, false, "Challenge successfully validated"));
                })
                .defaultIfEmpty(new ValidationResultDTO(false, true, "Challenge not found"));
    }
}
