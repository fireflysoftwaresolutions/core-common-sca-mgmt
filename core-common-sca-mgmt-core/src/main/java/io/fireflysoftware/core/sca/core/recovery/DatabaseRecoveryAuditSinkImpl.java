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
import io.fireflysoftware.core.sca.models.entities.SCARecoveryBoundaryAudit;
import io.fireflysoftware.core.sca.models.repositories.SCARecoveryBoundaryAuditRepository;
import io.fireflysoftware.core.sca.models.repositories.SCARecoveryOperationRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import java.util.Set;
import java.util.UUID;

@Component("databaseRecoveryAuditSink")
@ConditionalOnProperty(name = {"firefly.sca.recovery.enabled", "firefly.sca.recovery.database-audit.enabled"}, havingValue = "true")
public final class DatabaseRecoveryAuditSinkImpl {
    private final SCARecoveryBoundaryAuditRepository audits;
    private final SCARecoveryOperationRepository clock;
    private final TransactionalOperator transactions;

    public DatabaseRecoveryAuditSinkImpl(SCARecoveryBoundaryAuditRepository audits, SCARecoveryOperationRepository clock,
            @Qualifier("recoveryTransactionOperator") TransactionalOperator transactions) {
        this.audits = audits;
        this.clock = clock;
        this.transactions = transactions;
    }

    public Mono<RecoveryRejectionAuditReceipt> record(RecoveryAuditActor actor, RecoveryAction action, String outcome) {
        return Mono.defer(() -> {
            if (!Set.of("ADMITTED", "REFUSED", "REJECTED").contains(outcome)) return Mono.error(new RecoveryDeniedException());
            return transactions.execute(status -> clock.databaseNow().flatMap(now -> {
                SCARecoveryBoundaryAudit audit = new SCARecoveryBoundaryAudit();
                audit.setId(UUID.randomUUID());
                audit.setAction(action == null ? null : action.name());
                audit.setOutcome(outcome);
                audit.setOccurredAt(now);
                if (actor != null) {
                    audit.setIssuer(actor.issuer());
                    audit.setTenant(actor.tenant());
                    audit.setActorSubject(actor.subject());
                    audit.setClientId(actor.clientId());
                    audit.setPopulation(actor.population());
                }
                return audits.save(audit).map(saved -> new RecoveryRejectionAuditReceipt(saved.getId(), now));
            })).singleOrEmpty().switchIfEmpty(Mono.error(new RecoveryUnavailableException()));
        });
    }
}
