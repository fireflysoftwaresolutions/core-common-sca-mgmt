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

package io.fireflysoftware.core.sca.web.recovery.remote;

import io.fireflysoftware.core.sca.interfaces.recovery.*;
import java.util.UUID;

/** Private wire envelope; only the validated request-local assertion and opaque evidence may cross. */
record NativeOwnerRpc(String actorAssertion, RecoveryBinding binding, UUID operationId, UUID challengeId,
        UUID reservationId, String providerChallengeId, RecoveryFactorCategory category, String attestation,
        UUID executionId, RecoveryExecutionOutcome outcome, RecoveryAction action) {
    NativeOwnerRpc withAssertion(String assertion) {
        return new NativeOwnerRpc(assertion, binding, operationId, challengeId, reservationId, providerChallengeId,
                category, attestation, executionId, outcome, action);
    }
    @Override public String toString() { return "NativeOwnerRpc[REDACTED]"; }
}
