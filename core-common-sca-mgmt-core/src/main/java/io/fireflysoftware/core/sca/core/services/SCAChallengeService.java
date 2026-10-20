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
import io.fireflysoftware.core.sca.interfaces.dtos.SCAChallengeDTO;
import io.fireflysoftware.core.sca.interfaces.dtos.ValidationResultDTO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Defines operations for handling SCAChallenge records:
 * listing, creating, validating specific OTP challenges, etc.
 */
public interface SCAChallengeService {

    Mono<PaginationResponse<SCAChallengeDTO>> findAllByOperationId(UUID operationId, PaginationRequest paginationRequest);
    Mono<SCAChallengeDTO> create(UUID operationId, SCAChallengeDTO dto);
    Mono<SCAChallengeDTO> findById(UUID operationId, UUID challengeId);
    Mono<SCAChallengeDTO> update(UUID operationId, UUID challengeId, SCAChallengeDTO dto);
    Mono<Void> delete(UUID operationId, UUID challengeId);
    Mono<SCAChallengeDTO> findActiveChallengeForOperation(UUID operationId);

    /**
     * Validate a single challenge with the given user code (OTP).
     */
    Mono<ValidationResultDTO> validateChallenge(UUID operationId, UUID challengeId, String userCode);
}