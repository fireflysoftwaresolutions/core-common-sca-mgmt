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

import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryFactorCategory;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryProofKind;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * A qualified enrolled-factor owner. Must authenticate/verify issuer, audience, namespace, challenge,
 * subject, expiry, proof replay, enrollment and independent factor provenance itself. The independenceId
 * must be a stable canonical common-origin identifier across providers/enrollments, never a newly
 * generated challenge identifier; two checks of the same device or secret cannot become independent.
 * Knowledge is
 * verified by the identity provider; Core accepts only an opaque provider attestation for KNOWLEDGE.
 * Provider outages fail closed; sandbox adapters and client-supplied success are never accepted.
 */
public interface RecoveryFactorProvider {
    default boolean isQualified() { return false; }
    Mono<IssuedRecoveryChallenge> issue(TrustedRecoveryContext context, UUID operationId, UUID reservationId, RecoveryFactorCategory category);
    Mono<VerifiedRecoveryProof> verify(TrustedRecoveryContext context, RecoveryChallengeReference challenge,
            RecoveryProofKind proofKind, String response);
}
