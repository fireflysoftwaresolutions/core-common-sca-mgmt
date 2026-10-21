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
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Stores the one-time code or challenge tokens related to an SCA operation.
 */
@Data
@Table("sca_challenge")
public class SCAChallenge implements Persistable<UUID> {

    @Id
    @Column("id")
    private UUID id;

    @Column("sca_operation_id")
    private UUID scaOperationId; // references sca_operation.id

    @Column("challenge_code")
    private String challengeCode; // the one-time code/OTP token

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("expires_at")
    private LocalDateTime expiresAt;

    @Column("used")
    private Boolean used; // flag indicating if the code was used

    /** True only for a row that has not been inserted. A preset id would otherwise be treated as an update. */
    @Transient
    private boolean insert;

    @Override
    public boolean isNew() {
        return insert;
    }

    public void markNew() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        insert = true;
    }
}
