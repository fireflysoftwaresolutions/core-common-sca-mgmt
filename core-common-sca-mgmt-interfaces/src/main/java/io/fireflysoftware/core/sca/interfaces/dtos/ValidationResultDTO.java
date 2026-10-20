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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * DTO indicating the outcome of an SCA validation,
 * such as checking an OTP code or finalizing an SCA operation.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class ValidationResultDTO {

    /**
     * Indicates if the validation attempt was successful (true) or not (false).
     */
    private boolean success;

    /**
     * Indicates if the SCA operation or challenge is locked or failed
     * (e.g., max retries exceeded, status=FAILED/LOCKED).
     */
    private boolean lockedOrFailed;

    /**
     * An optional message describing the result.
     * Could be "SCA Verified", "Wrong Code", "Max Retries Exceeded", etc.
     */
    private String message;

}