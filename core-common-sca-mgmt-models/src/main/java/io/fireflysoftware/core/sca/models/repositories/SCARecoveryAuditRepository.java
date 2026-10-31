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

import io.fireflysoftware.core.sca.models.entities.SCARecoveryAudit;
import org.springframework.data.repository.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Deliberately exposes insert-only save; the database role also forbids mutation/deletion. */
public interface SCARecoveryAuditRepository extends Repository<SCARecoveryAudit, UUID> {
    Mono<Boolean> existsByOperationIdAndEventTypeAndReservationId(UUID operationId, String eventType, UUID reservationId);
    Mono<Boolean> existsByOperationIdAndEventTypeAndExecutionId(UUID operationId, String eventType, UUID executionId);

    <S extends SCARecoveryAudit> Mono<S> save(S audit);
}
