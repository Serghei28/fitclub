package com.fitclub.payment.infrastructure.config;

import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fitclub.common.http.InternalServiceClient;
import com.fitclub.payment.application.port.PaymentPorts.MembershipOrderReader;
import com.fitclub.payment.application.port.PaymentPorts.TransactionRunner;
import com.fitclub.payment.infrastructure.http.MembershipOrderClient;

/** Сборка адаптеров payment-service. PaymentProvider подставляется по типу (SimulatorPaymentProvider). */
@Configuration(proxyBeanMethods = false)
class PaymentInfrastructureConfiguration {

    @Bean
    MembershipOrderReader membershipOrderReader(PaymentProperties properties) {
        PaymentProperties.MembershipClient c = properties.membership();
        return new MembershipOrderClient(new InternalServiceClient("membership-service", c.baseUrl(), c.serviceToken(),
                c.connectTimeout(), c.readTimeout(), c.maxAttempts(), c.retryBackoff()));
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
