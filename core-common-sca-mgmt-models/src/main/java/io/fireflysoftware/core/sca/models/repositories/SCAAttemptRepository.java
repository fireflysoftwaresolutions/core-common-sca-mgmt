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


package io.fireflysoftware.core.sca.models.repositories;

import io.fireflysoftware.core.sca.models.entities.SCAAttempt;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;

public interface SCAAttemptRepository extends BaseRepository<SCAAttempt, UUID> {
    Flux<SCAAttempt> findAllByScaChallengeId(UUID scaChallengeId, Pageable pageable);
    Mono<Long> countByScaChallengeId(UUID scaChallengeId);
}
