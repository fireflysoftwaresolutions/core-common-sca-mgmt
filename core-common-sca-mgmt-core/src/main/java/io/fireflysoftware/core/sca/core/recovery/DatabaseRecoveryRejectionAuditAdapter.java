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

package io.fireflysoftware.core.sca.core.recovery;

import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = {"firefly.sca.recovery.enabled", "firefly.sca.recovery.database-audit.enabled"}, havingValue = "true")
public final class DatabaseRecoveryRejectionAuditAdapter implements RecoveryRejectionAuditPort {
    private final DatabaseRecoveryAuditSink sink;
    public DatabaseRecoveryRejectionAuditAdapter(DatabaseRecoveryAuditSink sink) { this.sink = sink; }
    @Override public boolean isQualified() { return true; }
    @Override public Mono<RecoveryRejectionAuditReceipt> recordRejection(TrustedRecoveryContext context, RecoveryAction action) {
        var binding = context.binding();
        return sink.record(new RecoveryAuditActor(binding.issuer(), binding.tenant(), binding.actorSubject(),
                binding.clientId(), binding.population()), action, "REJECTED");
    }
}
