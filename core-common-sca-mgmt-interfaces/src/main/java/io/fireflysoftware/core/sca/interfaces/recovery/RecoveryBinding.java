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

package io.fireflysoftware.core.sca.interfaces.recovery;

public record RecoveryBinding(String issuer, String tenant, String clientId, String delegatingClientId,
        String actorSubject, String targetIssuer, String targetRealm, String targetSubject,
        String population, String purpose, String lockGeneration) {
    public RecoveryBinding {
        for (String value : new String[] { issuer, tenant, clientId, actorSubject, targetIssuer,
                targetRealm, targetSubject, population, purpose, lockGeneration }) {
            if (value == null || value.isBlank() || value.length() > 512) {
                throw new IllegalArgumentException("Invalid recovery binding");
            }
        }
        if (delegatingClientId != null && (delegatingClientId.isBlank() || delegatingClientId.length() > 512)) {
            throw new IllegalArgumentException("Invalid recovery binding");
        }
    }
    @Override public String toString() { return "RecoveryBinding[REDACTED]"; }
}
