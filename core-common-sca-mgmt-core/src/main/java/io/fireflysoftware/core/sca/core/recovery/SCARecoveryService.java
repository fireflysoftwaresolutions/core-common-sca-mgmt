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

import io.fireflysoftware.core.sca.interfaces.recovery.ConsumeRecoveryAuthorizationRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.CreateRecoveryOperationRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.IssueRecoveryChallengeRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.RecordRecoveryExecutionRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryAuthorizationReceipt;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryChallengeResponse;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryOperationResponse;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryVerificationResponse;
import io.fireflysoftware.core.sca.interfaces.recovery.VerifyRecoveryChallengeRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.ReserveRecoveryVerificationRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.RecoveryVerificationReservation;
import io.fireflysoftware.core.sca.interfaces.recovery.CompleteRecoveryVerificationRequest;
import io.fireflysoftware.core.sca.interfaces.recovery.VerifyRecoveryAuthorizationRequest;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SCARecoveryService {
    Mono<RecoveryOperationResponse> createRecoveryOperation(CreateRecoveryOperationRequest request);
    Mono<RecoveryChallengeResponse> issueRecoveryChallenge(UUID operationId, IssueRecoveryChallengeRequest request);
    Mono<RecoveryVerificationResponse> verifyRecoveryChallenge(UUID operationId, VerifyRecoveryChallengeRequest request);
    Mono<RecoveryVerificationReservation> reserveRecoveryVerification(UUID operationId, ReserveRecoveryVerificationRequest request);
    Mono<RecoveryVerificationResponse> completeRecoveryVerification(UUID operationId, CompleteRecoveryVerificationRequest request);
    Mono<RecoveryAuthorizationReceipt> verifyRecoveryAuthorization(UUID operationId, VerifyRecoveryAuthorizationRequest request);
    Mono<RecoveryAuthorizationReceipt> consumeRecoveryAuthorization(UUID operationId, ConsumeRecoveryAuthorizationRequest request);
    Mono<Void> recordRecoveryExecution(UUID operationId, RecordRecoveryExecutionRequest request);
}
