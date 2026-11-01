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
import reactor.core.publisher.Mono;

/**
 * A qualified durable append-only boundary audit, not a logging adapter. Record only this admitted
 * context, source action and generic rejection; never raw request/proof or unknown/foreign IDs.
 * Completion must follow durable commit. Missing acknowledgement or collector outage fails closed.
 * Malformed requests and missing receiver principals remain the receiving boundary's audit duty.
 */
public interface RecoveryRejectionAuditPort {
    default boolean isQualified() { return false; }
    Mono<RecoveryRejectionAuditReceipt> recordRejection(TrustedRecoveryContext context, RecoveryAction action);
}
