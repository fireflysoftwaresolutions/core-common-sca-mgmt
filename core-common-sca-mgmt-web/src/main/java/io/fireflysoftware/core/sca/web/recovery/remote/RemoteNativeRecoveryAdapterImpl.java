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

import io.fireflysoftware.core.sca.core.recovery.*;
import io.fireflysoftware.core.sca.interfaces.recovery.*;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.UUID;

/** Actual native-owner RPC ports. Qualification is explicit configuration, never a success stub. */
public final class RemoteNativeRecoveryAdapterImpl implements RecoveryAdmissionPort, RecoveryFactorProvider, RecoveryExecutionVerifier {
    private final NativeOwnerRpcClientImpl client;
    private final String providerId;
    public RemoteNativeRecoveryAdapterImpl(NativeOwnerRpcClientImpl client, NativeOwnerProperties properties) {
        this.client = client;
        providerId = properties.getProviderId();
    }
    @Override public boolean isQualified() { return true; }

    @Override public Mono<TrustedRecoveryContext> admit(RecoveryBinding expected, RecoveryAction action) {
        return client.call("admit", new NativeOwnerRpc(null, expected, null, null, null, null, null, null, null, null, action), TrustedRecoveryContext.class)
                .flatMap(context -> client.verifiedAssertion(expected).map(assertion -> {
                    require(expected.equals(context.binding()) && context.action() == action && text(context.ownerLinkId())
                            && text(context.identityLinkAttestationId()) && text(context.lockAttestationId()) && live(context.validUntil()));
                    Instant until = context.validUntil().isBefore(assertion.getExpiresAt()) ? context.validUntil() : assertion.getExpiresAt();
                    return new TrustedRecoveryContext(expected, context.ownerLinkId(), context.identityLinkAttestationId(), context.lockAttestationId(), until, action);
                }));
    }

    @Override public Mono<IssuedRecoveryChallenge> issue(TrustedRecoveryContext context, UUID operationId, UUID reservationId,
            RecoveryFactorCategory category) {
        return Mono.defer(() -> {
            require(context.action() == RecoveryAction.ISSUE_CHALLENGE && operationId != null && reservationId != null && category != null);
            return client.call("issue", new NativeOwnerRpc(null, context.binding(), operationId, null, reservationId, null,
                    category, null, null, null, context.action()), IssuedRecoveryChallenge.class).map(issued -> {
                require(context.binding().equals(issued.binding()) && operationId.equals(issued.operationId())
                        && reservationId.equals(issued.reservationId()) && category == issued.category()
                        && providerId.equals(issued.providerId()) && text(issued.enrollmentId()) && text(issued.independenceId())
                        && text(issued.providerChallengeId()) && live(issued.expiresAt()));
                return issued;
            });
        });
    }

    @Override public Mono<VerifiedRecoveryProof> verify(TrustedRecoveryContext context, RecoveryChallengeReference reference,
            RecoveryProofKind kind, String response) {
        return Mono.defer(() -> {
            require((context.action() == RecoveryAction.COMPLETE_VERIFICATION || context.action() == RecoveryAction.VERIFY_CHALLENGE)
                    && kind == RecoveryProofKind.PROVIDER_ATTESTATION && text(response) && response.length() <= 128
                    && reference.reservationId() != null && context.binding().equals(reference.binding()) && live(reference.expiresAt()));
            return client.call("verify", new NativeOwnerRpc(null, context.binding(), reference.operationId(), reference.challengeId(),
                    reference.reservationId(), reference.providerChallengeId(), reference.category(), response, null, null, context.action()),
                    VerifiedRecoveryProof.class).map(proof -> {
                require(context.binding().equals(proof.binding()) && reference.operationId().equals(proof.operationId())
                        && reference.challengeId().equals(proof.challengeId()) && reference.reservationId().equals(proof.reservationId())
                        && reference.category() == proof.category() && providerId.equals(proof.providerId())
                        && reference.providerId().equals(proof.providerId()) && reference.enrollmentId().equals(proof.enrollmentId())
                        && reference.independenceId().equals(proof.independenceId())
                        && reference.providerChallengeId().equals(proof.providerChallengeId()) && live(proof.expiresAt())
                        && (!proof.accepted() || response.equals(proof.attestationId())));
                return proof;
            });
        });
    }

    @Override public Mono<VerifiedRecoveryExecution> verify(TrustedRecoveryContext context, UUID executionId, RecoveryExecutionOutcome expected) {
        return Mono.defer(() -> {
            require(context.action() == RecoveryAction.RECORD_EXECUTION && executionId != null && expected != null);
            return client.call("journal", new NativeOwnerRpc(null, context.binding(), null, null, null, null, null,
                    null, executionId, expected, context.action()), VerifiedRecoveryExecution.class).map(execution -> {
                require(context.binding().equals(execution.binding()) && executionId.equals(execution.executionId()) && expected == execution.outcome());
                return execution;
            });
        });
    }
    private static boolean text(String value) { return value != null && !value.isBlank() && value.length() <= 512; }
    private static boolean live(Instant value) { return value != null && Instant.now().isBefore(value); }
    private static void require(boolean condition) { if (!condition) throw new RecoveryDeniedException(); }
}
