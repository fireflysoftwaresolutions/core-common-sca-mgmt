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

import io.fireflysoftware.core.sca.interfaces.enums.SCAStatusEnum;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Stores a record of status changes or events for each SCA operation.
 */
@Data
@Table("sca_operation_history")
public class SCAOperationHistory {

    @Id
    @Column("id")
    private UUID id;

    @Column("sca_operation_id")
    private UUID scaOperationId;

    @Column("status")
    private SCAStatusEnum status;

    @Column("event_time")
    private LocalDateTime eventTime;

    @Column("comments")
    private String comments;
}

