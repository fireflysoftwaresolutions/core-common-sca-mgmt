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

package io.fireflysoftware.core.sca.web.recovery;

import io.fireflysoftware.core.sca.core.recovery.DatabaseRecoveryAuditSinkImpl;
import io.fireflysoftware.core.sca.core.recovery.RecoveryAuditActor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("databaseRecoveryBoundaryAuditAdapter")
@ConditionalOnProperty(name = {"firefly.sca.recovery.enabled", "firefly.sca.recovery.database-audit.enabled"}, havingValue = "true")
public final class DatabaseRecoveryBoundaryAuditAdapterImpl implements RecoveryBoundaryAuditPort {
    private final DatabaseRecoveryAuditSinkImpl sink;
    public DatabaseRecoveryBoundaryAuditAdapterImpl(DatabaseRecoveryAuditSinkImpl sink) { this.sink = sink; }
    @Override public boolean isQualified() { return true; }
    @Override public Mono<Void> record(Event event) {
        var actor = event.actor();
        return sink.record(actor == null ? null : new RecoveryAuditActor(actor.issuer(), actor.tenant(), actor.subject(),
                actor.clientId(), actor.population()), event.action(), event.outcome().name()).then();
    }
}
