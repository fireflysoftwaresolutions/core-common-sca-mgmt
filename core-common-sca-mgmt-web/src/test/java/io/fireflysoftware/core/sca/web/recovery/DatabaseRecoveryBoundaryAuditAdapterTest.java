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
import io.fireflysoftware.core.sca.core.recovery.RecoveryRejectionAuditReceipt;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryAction;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.Mockito.*;

class DatabaseRecoveryBoundaryAuditAdapterTest {
    @Test
    void verifiedAndAnonymousBoundaryEventsUseTheCommittedOwningSink() {
        var sink = mock(DatabaseRecoveryAuditSinkImpl.class);
        var adapter = new DatabaseRecoveryBoundaryAuditAdapterImpl(sink);
        var actor = new RecoveryBoundaryAuditPort.VerifiedActor("issuer", "tenant", "actor", "client", "HUMAN");
        var expected = new RecoveryAuditActor("issuer", "tenant", "actor", "client", "HUMAN");
        when(sink.record(expected, RecoveryAction.RESERVE_VERIFICATION, "ADMITTED"))
                .thenReturn(Mono.just(new RecoveryRejectionAuditReceipt(UUID.randomUUID(), Instant.now())));
        when(sink.record(null, null, "REFUSED")).thenReturn(Mono.just(new RecoveryRejectionAuditReceipt(UUID.randomUUID(), Instant.now())));
        StepVerifier.create(adapter.record(new RecoveryBoundaryAuditPort.Event(actor, RecoveryAction.RESERVE_VERIFICATION,
                RecoveryBoundaryAuditPort.Outcome.ADMITTED))).verifyComplete();
        StepVerifier.create(adapter.record(new RecoveryBoundaryAuditPort.Event(null, null, RecoveryBoundaryAuditPort.Outcome.REFUSED))).verifyComplete();
        verify(sink).record(expected, RecoveryAction.RESERVE_VERIFICATION, "ADMITTED");
        verify(sink).record(null, null, "REFUSED");
        when(sink.record(null, null, "REFUSED")).thenReturn(Mono.error(new IllegalStateException("sink unavailable")));
        StepVerifier.create(adapter.record(new RecoveryBoundaryAuditPort.Event(null, null, RecoveryBoundaryAuditPort.Outcome.REFUSED)))
                .expectError(IllegalStateException.class).verify();
    }
}
