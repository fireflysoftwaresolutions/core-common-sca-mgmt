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

package io.fireflysoftware.core.sca.web.recovery.remote;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "firefly.sca.recovery.native-owner")
public class NativeOwnerProperties {
    private URI baseUrl;
    private String providerId;
    private String workloadClientId;
    private Path workloadSecretFile;
    private Duration timeout = Duration.ofSeconds(5);
    private int maxResponseBytes = 65536;

    void validate(String issuer, String audience) {
        if (baseUrl == null || !"https".equals(baseUrl.getScheme()) || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null || baseUrl.getRawQuery() != null || baseUrl.getFragment() != null
                || issuer == null || !baseUrl.toString().equals(issuer + "/firefly-recovery/rpc")
                || !baseUrl.getRawPath().matches("/realms/[^/]+/firefly-recovery/rpc")
                || audience == null || audience.isBlank() || providerId == null || providerId.isBlank()
                || workloadClientId == null || workloadClientId.isBlank() || workloadClientId.length() > 512
                || workloadClientId.indexOf(':') >= 0 || workloadClientId.chars().anyMatch(Character::isISOControl)
                || workloadSecretFile == null || !workloadSecretFile.isAbsolute()
                || timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(10)) > 0
                || maxResponseBytes < 1024 || maxResponseBytes > 65536) {
            throw new IllegalStateException("Explicit HTTPS native owner, bounded transport and mounted workload credential required");
        }
    }
    public URI getBaseUrl() { return baseUrl; }
    public void setBaseUrl(URI value) { baseUrl = value; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String value) { providerId = value; }
    public String getWorkloadClientId() { return workloadClientId; }
    public void setWorkloadClientId(String value) { workloadClientId = value; }
    public Path getWorkloadSecretFile() { return workloadSecretFile; }
    public void setWorkloadSecretFile(Path value) { workloadSecretFile = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int value) { maxResponseBytes = value; }
    @Override public String toString() { return "NativeOwnerProperties[REDACTED]"; }
}
