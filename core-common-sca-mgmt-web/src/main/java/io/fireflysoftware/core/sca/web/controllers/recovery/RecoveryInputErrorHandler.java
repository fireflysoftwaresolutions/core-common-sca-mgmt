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


package io.fireflysoftware.core.sca.web.controllers.recovery;

import io.fireflysoftware.core.sca.core.recovery.RecoveryProperties;
import io.fireflysoftware.core.sca.web.recovery.RecoveryBoundaryAuditPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Malformed input is audited without serializing its body, URI, target, factor or parsing error. */
@RestControllerAdvice(assignableTypes = SCARecoveryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RecoveryInputErrorHandler {
    private final ObjectProvider<RecoveryBoundaryAuditPort> audits;
    private final RecoveryProperties properties;

    public RecoveryInputErrorHandler(ObjectProvider<RecoveryBoundaryAuditPort> audits, RecoveryProperties properties) {
        this.audits = audits;
        this.properties = properties;
    }

    @ExceptionHandler(ServerWebInputException.class)
    public Mono<ResponseEntity<Void>> malformedInput() {
        return Mono.defer(() -> {
            RecoveryBoundaryAuditPort audit = audits.getIfAvailable();
            if (!properties.isEnabled() || audit == null || !audit.isQualified()) {
                return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).<Void>build());
            }
            return Mono.defer(() -> audit.record(new RecoveryBoundaryAuditPort.Event(null, null, RecoveryBoundaryAuditPort.Outcome.REFUSED)))
                    .timeout(Duration.ofSeconds(5)).thenReturn(ResponseEntity.badRequest().<Void>build());
        }).onErrorReturn(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).<Void>build());
    }
}
