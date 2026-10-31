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

package io.fireflysoftware.core.sca.web.recovery;

import org.fireflyframework.security.webflux.authentication.FireflyAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/** Request-local assertion preserved only after the real decoder and Jafly principal conversion. */
public final class RecoveryAuthenticationToken extends FireflyAuthenticationToken {
    private transient Jwt assertion;

    public RecoveryAuthenticationToken(FireflyAuthenticationToken converted, Jwt assertion) {
        super(converted.securityPrincipal());
        if (!converted.isAuthenticated() || assertion == null) throw new IllegalArgumentException("Verified recovery authentication required");
        this.assertion = assertion;
    }
    @Override public Object getCredentials() { return assertion; }
    @Override public void eraseCredentials() { super.eraseCredentials(); assertion = null; }
    @Override public String toString() { return "RecoveryAuthenticationToken[REDACTED]"; }
}
