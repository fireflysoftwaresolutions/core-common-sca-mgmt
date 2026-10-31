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

package io.fireflysoftware.core.sca.models.entities;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Table("sca_recovery_operation")
public class SCARecoveryOperation {
    @Id
    @Column("id")
    private UUID id;

    @Version
    @Column("version")
    private Long version;

    @Column("issuer")
    private String issuer;

    @Column("tenant")
    private String tenant;

    @Column("client_id")
    private String clientId;

    @Column("delegating_client_id")
    private String delegatingClientId;

    @Column("actor_subject")
    private String actorSubject;

    @Column("target_issuer")
    private String targetIssuer;

    @Column("target_realm")
    private String targetRealm;

    @Column("target_subject")
    private String targetSubject;

    @Column("population")
    private String population;

    @Column("purpose")
    private String purpose;

    @Column("lock_generation")
    private String lockGeneration;

    @Column("owner_link_id")
    private String ownerLinkId;

    @Column("identity_link_attestation_id")
    private String identityLinkAttestationId;

    @Column("lock_attestation_id")
    private String lockAttestationId;

    @Column("create_key")
    private String createKey;

    @Column("state")
    private String state;

    @Column("created_at")
    private Instant createdAt;

    @Column("expires_at")
    private Instant expiresAt;

    @Column("attempt_count")
    private Integer attemptCount;

    @Column("max_attempts")
    private Integer maxAttempts;

    @Column("challenge_count")
    private Integer challengeCount;

    @Column("max_challenges")
    private Integer maxChallenges;

    @Column("execution_id")
    private UUID executionId;

    @Column("consume_key")
    private String consumeKey;

    @Column("receipt_expires_at")
    private Instant receiptExpiresAt;

    @Column("execution_outcome")
    private String executionOutcome;

}
