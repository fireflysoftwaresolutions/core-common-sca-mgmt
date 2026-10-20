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


package io.fireflysoftware.core.sca.interfaces.dtos;

import lombok.Data;

/**
 * A generic error response DTO for SCA operations,
 * providing a code, message, and optional details.
 */
@Data
public class SCAErrorDTO {

    /**
     * A short error code (e.g., "SCA-LOCKED", "SCA-INVALID").
     */
    private String errorCode;

    /**
     * A human-readable message describing the error.
     */
    private String errorMessage;

    /**
     * Additional details if needed (stack trace, fields, etc.).
     */
    private String errorDetails;
}
