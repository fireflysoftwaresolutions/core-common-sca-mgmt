/*
 * Copyright 2026 Firefly Software Foundation
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

import io.fireflysoftware.core.sca.models.entities.SCARecoveryOperation;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface SCARecoveryOperationRepository extends ReactiveCrudRepository<SCARecoveryOperation, UUID> {
    Mono<SCARecoveryOperation> findByIssuerAndTenantAndClientIdAndActorSubjectAndCreateKey(
            String issuer, String tenant, String clientId, String actorSubject, String createKey);

    @Query("SELECT clock_timestamp()")
    Mono<Instant> databaseNow();
}
