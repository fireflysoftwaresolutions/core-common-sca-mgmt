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
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryBinding;

import java.time.Instant;

/** Derived by the qualified receiver/owner capability, never constructed from request assertions. */
public record TrustedRecoveryContext(RecoveryBinding binding, String ownerLinkId,
        String identityLinkAttestationId, String lockAttestationId, Instant validUntil, RecoveryAction action) {
    @Override public String toString() { return "TrustedRecoveryContext[REDACTED]"; }
}
