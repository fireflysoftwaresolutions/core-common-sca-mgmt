/*
 * Copyright 2025 Firefly Software Foundation
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

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks each user attempt to enter a challenge code.
 */
@Data
@Table("sca_attempt")
public class SCAAttempt {

    @Id
    @Column("id")
    private UUID id;

    @Column("sca_challenge_id")
    private UUID scaChallengeId; // references sca_challenge.id

    @Column("attempt_value")
    private String attemptValue; // code the user entered

    @Column("attempted_at")
    private LocalDateTime attemptedAt; // or LocalDateTime

    @Column("success")
    private Boolean success; // whether the attempt was successful

    @Column("ip_address")
    private String ipAddress; // optional IP from which the attempt was made
}

