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
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Table("sca_recovery_boundary_audit")
public class SCARecoveryBoundaryAudit implements org.springframework.data.domain.Persistable<UUID> {
    @Id
    @Column("id")
    private UUID id;

    @Column("issuer")
    private String issuer;

    @Column("tenant")
    private String tenant;

    @Column("actor_subject")
    private String actorSubject;

    @Column("client_id")
    private String clientId;

    @Column("population")
    private String population;

    @Column("action")
    private String action;

    @Column("outcome")
    private String outcome;

    @Column("occurred_at")
    private Instant occurredAt;

    @Override public boolean isNew() { return true; }
}
