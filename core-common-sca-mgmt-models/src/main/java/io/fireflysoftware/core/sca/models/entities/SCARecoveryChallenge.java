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
@Table("sca_recovery_challenge")
public class SCARecoveryChallenge {
    @Id
    @Column("id")
    private UUID id;

    @Version
    @Column("version")
    private Long version;

    @Column("operation_id")
    private UUID operationId;

    @Column("issue_reservation_id")
    private UUID issueReservationId;

    @Column("verification_reservation_id")
    private UUID verificationReservationId;

    @Column("verification_reserved_at")
    private Instant verificationReservedAt;

    @Column("category")
    private String category;

    @Column("provider_id")
    private String providerId;

    @Column("enrollment_id")
    private String enrollmentId;

    @Column("independence_id")
    private String independenceId;

    @Column("provider_challenge_id")
    private String providerChallengeId;

    @Column("created_at")
    private Instant createdAt;

    @Column("expires_at")
    private Instant expiresAt;

    @Column("attempt_count")
    private Integer attemptCount;

    @Column("max_attempts")
    private Integer maxAttempts;

    @Column("verified")
    private Boolean verified;

    @Column("attestation_id")
    private String attestationId;

    @Column("proof_expires_at")
    private Instant proofExpiresAt;

}
