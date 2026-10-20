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
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.sca.core.mappers.SCAOperationMapper;
import io.fireflysoftware.core.sca.interfaces.dtos.SCAOperationDTO;
import io.fireflysoftware.core.sca.interfaces.dtos.ValidationResultDTO;
import io.fireflysoftware.core.sca.interfaces.enums.SCAStatusEnum;
import io.fireflysoftware.core.sca.models.entities.SCAOperation;
import io.fireflysoftware.core.sca.models.repositories.SCAOperationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Transactional
public class SCAOperationServiceImpl implements SCAOperationService {

    @Autowired
    private SCAChallengeService challengeService;


    @Autowired
    private SCAOperationRepository repository;

    @Autowired
    private SCAOperationMapper mapper;

    @Override
    public Mono<PaginationResponse<SCAOperationDTO>> filterAll(FilterRequest<SCAOperationDTO> filterRequest) {
        return FilterUtils.createFilter(
                        SCAOperation.class,
                        mapper::toDTO
                ).filter(filterRequest)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<SCAOperationDTO> create(SCAOperationDTO dto) {
        return Mono.just(dto)
                .map(mapper::toEntity)
                .flatMap(entity -> {
                    entity.setCreatedAt(LocalDateTime.now());
                    entity.setLastUpdated(LocalDateTime.now());
                    return repository.save(entity);
                })
                .map(mapper::toDTO)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<SCAOperationDTO> findById(UUID operationId) {
        return repository.findById(operationId)
                .map(mapper::toDTO)
                .switchIfEmpty(Mono.error(new RuntimeException("SCA Operation not found")))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<SCAOperationDTO> update(UUID operationId, SCAOperationDTO dto) {
        return repository.findById(operationId)
                .flatMap(existing -> {
                    SCAOperation updatedEntity = mapper.toEntity(dto);
                    updatedEntity.setId(operationId);
                    updatedEntity.setCreatedAt(existing.getCreatedAt());
                    updatedEntity.setLastUpdated(LocalDateTime.now());
                    return repository.save(updatedEntity);
                })
                .map(mapper::toDTO)
                .switchIfEmpty(Mono.error(new RuntimeException("SCA Operation not found")))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> delete(UUID operationId) {
        return repository.findById(operationId)
                .flatMap(repository::delete)
                .switchIfEmpty(Mono.error(new RuntimeException("SCA Operation not found")))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> triggerSCA(UUID operationId) {

        return repository.findById(operationId)
                .flatMap(existing -> {
                    existing.setStatus(SCAStatusEnum.PENDING);
                    existing.setLastUpdated(LocalDateTime.now());
                    return repository.save(existing);
                })
                .switchIfEmpty(Mono.error(new RuntimeException("SCA Operation not found")))
                .then()
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     *  1) We first find the SCA operation to ensure it's PENDING.
     *  2) We then find an "active" challenge for that operation (not used, not expired).
     *  3) We call challengeService.validateChallenge(operationId, challengeId, userCode).
     *  4) We update the operation status to VERIFIED or FAILED depending on the validation result.
     */
    @Override
    public Mono<ValidationResultDTO> validateSCA(UUID operationId, String userCode) {
        return repository.findById(operationId)
                .flatMap(existingOperation -> {

                    // 1) Ensure operation is in a valid status for validation
                    if (existingOperation.getStatus() != SCAStatusEnum.PENDING) {
                        return Mono.just(new ValidationResultDTO(
                                false,
                                false,
                                "SCA Operation not in a valid state for validation"
                        ));
                    }

                    // 2) Find an active challenge for this operation (not used, not expired, etc.)
                    //    For example: challengeService might expose a method "findActiveChallengeForOperation(...)"
                    //    that returns a Mono<SCAChallenge> or Mono.empty() if none found.
                    return challengeService.findActiveChallengeForOperation(operationId)
                            .flatMap(activeChallenge ->
                                    // 3) Now validate the user code for that specific challenge
                                    challengeService.validateChallenge(operationId, activeChallenge.getId(), userCode)
                            )
                            .flatMap(validationResult -> {
                                // 4) Update the operation status based on whether code is valid
                                existingOperation.setStatus(
                                        validationResult.isSuccess() ? SCAStatusEnum.VERIFIED : SCAStatusEnum.FAILED
                                );
                                existingOperation.setLastUpdated(LocalDateTime.now());

                                // Save updates to SCAOperation in DB, then return the validation result
                                return repository.save(existingOperation)
                                        .thenReturn(validationResult);
                            })
                            .defaultIfEmpty(new ValidationResultDTO(
                                    false,
                                    false,
                                    "No active challenge found for this operation"
                            ));
                })
                .switchIfEmpty(Mono.error(new RuntimeException("SCA Operation not found")))
                .subscribeOn(Schedulers.boundedElastic());
    }
    
}