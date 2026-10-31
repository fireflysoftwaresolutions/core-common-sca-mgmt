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

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecoveryProperties.class)
public class RecoveryConfiguration {
    @Bean
    @ConditionalOnMissingBean(RecoveryAdmissionPort.class)
    @ConditionalOnProperty(name = "firefly.sca.recovery.native-owner.enabled", havingValue = "false", matchIfMissing = true)
    RecoveryAdmissionPort unavailableRecoveryAdmission() {
        return (expectations, action) -> Mono.error(new RecoveryUnavailableException());
    }

    @Bean
    @ConditionalOnMissingBean(RecoveryFactorProvider.class)
    @ConditionalOnProperty(name = "firefly.sca.recovery.native-owner.enabled", havingValue = "false", matchIfMissing = true)
    RecoveryFactorProvider unavailableRecoveryFactors() {
        return new RecoveryFactorProvider() {
            @Override public Mono<IssuedRecoveryChallenge> issue(TrustedRecoveryContext context, java.util.UUID id, java.util.UUID reservationId,
                    io.fireflysoftware.core.sca.interfaces.recovery.RecoveryFactorCategory category) {
                return Mono.error(new RecoveryUnavailableException());
            }
            @Override public Mono<VerifiedRecoveryProof> verify(TrustedRecoveryContext context,
                    RecoveryChallengeReference challenge,
                    io.fireflysoftware.core.sca.interfaces.recovery.RecoveryProofKind kind, String response) {
                return Mono.error(new RecoveryUnavailableException());
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(RecoveryExecutionVerifier.class)
    @ConditionalOnProperty(name = "firefly.sca.recovery.native-owner.enabled", havingValue = "false", matchIfMissing = true)
    RecoveryExecutionVerifier unavailableRecoveryExecutor() {
        return (context, execution, outcome) -> Mono.error(new RecoveryUnavailableException());
    }

    @Bean
    @ConditionalOnMissingBean(RecoveryRejectionAuditPort.class)
    @ConditionalOnProperty(name = "firefly.sca.recovery.database-audit.enabled", havingValue = "false", matchIfMissing = true)
    RecoveryRejectionAuditPort unavailableRecoveryRejectionAudit() {
        return (context, action) -> Mono.error(new RecoveryUnavailableException());
    }

    /** Uses the same unique ConnectionFactory that backs the owning application's R2DBC repositories. */
    @Bean("recoveryTransactionOperator")
    @ConditionalOnProperty(name = "firefly.sca.recovery.enabled", havingValue = "true")
    TransactionalOperator recoveryTransactionOperator(ConnectionFactory connectionFactory) {
        return TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
    }
}
