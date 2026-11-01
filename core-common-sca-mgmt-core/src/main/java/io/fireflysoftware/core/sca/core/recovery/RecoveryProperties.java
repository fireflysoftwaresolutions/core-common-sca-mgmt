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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Recovery budgets are unrelated to the identity owner's permanent authentication failure policy. */
@ConfigurationProperties(prefix = "firefly.sca.recovery")
public class RecoveryProperties {
    private boolean enabled;
    private int maxAttempts = 5;
    private int maxChallengeAttempts = 3;
    private int maxChallenges = 4;
    private Duration operationTtl = Duration.ofMinutes(10);
    private Duration challengeTtl = Duration.ofMinutes(3);
    private Duration receiptTtl = Duration.ofMinutes(1);

    public void validate() {
        if (maxAttempts < 2 || maxAttempts > 20 || maxChallengeAttempts < 1
                || maxChallengeAttempts > maxAttempts || maxChallenges < 2 || maxChallenges > 10) {
            throw new IllegalArgumentException("Recovery budgets are outside supported bounds");
        }
        if (!valid(operationTtl, Duration.ofMinutes(30)) || !valid(challengeTtl, operationTtl)
                || !valid(receiptTtl, Duration.ofMinutes(2)) || receiptTtl.compareTo(operationTtl) > 0) {
            throw new IllegalArgumentException("Recovery expiry is outside supported bounds");
        }
    }
    private boolean valid(Duration value, Duration maximum) {
        return value != null && !value.isZero() && !value.isNegative() && value.compareTo(maximum) <= 0;
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = value; }
    public int getMaxChallengeAttempts() { return maxChallengeAttempts; }
    public void setMaxChallengeAttempts(int value) { maxChallengeAttempts = value; }
    public int getMaxChallenges() { return maxChallenges; }
    public void setMaxChallenges(int value) { maxChallenges = value; }
    public Duration getOperationTtl() { return operationTtl; }
    public void setOperationTtl(Duration value) { operationTtl = value; }
    public Duration getChallengeTtl() { return challengeTtl; }
    public void setChallengeTtl(Duration value) { challengeTtl = value; }
    public Duration getReceiptTtl() { return receiptTtl; }
    public void setReceiptTtl(Duration value) { receiptTtl = value; }
}
