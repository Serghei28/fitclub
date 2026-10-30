package com.fitclub.schedule.infrastructure.config;

import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fitclub.common.http.InternalServiceClient;
import com.fitclub.schedule.application.port.TrainerDirectory;
import com.fitclub.schedule.application.port.TransactionRunner;
import com.fitclub.schedule.infrastructure.http.AuthTrainerDirectory;

/** Сборка адаптеров schedule-service: application получает только свои порты. */
@Configuration(proxyBeanMethods = false)
class ScheduleInfrastructureConfiguration {

    @Bean
    TrainerDirectory trainerDirectory(ScheduleProperties properties) {
        ScheduleProperties.AuthClient auth = properties.auth();
        return new AuthTrainerDirectory(new InternalServiceClient("auth-service", auth.baseUrl(),
                auth.serviceToken(), auth.connectTimeout(), auth.readTimeout(), auth.maxAttempts(),
                auth.retryBackoff()));
    }

    @Bean
    TransactionRunner transactionRunner(PlatformTransactionManager transactionManager) {
        TransactionTemplate readWrite = new TransactionTemplate(transactionManager);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return new TransactionRunner() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                return readWrite.execute(status -> work.get());
            }

            @Override
            public <T> T readOnly(Supplier<T> work) {
                return readOnly.execute(status -> work.get());
            }
        };
    }
}
