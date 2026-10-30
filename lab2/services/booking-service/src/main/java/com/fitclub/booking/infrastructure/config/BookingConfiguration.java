package com.fitclub.booking.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import java.util.function.Supplier;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import com.fitclub.booking.application.port.BookingPorts.MembershipEligibilityReader;
import com.fitclub.booking.application.port.BookingPorts.TransactionRunner;
import com.fitclub.booking.domain.BookingPolicy;
import com.fitclub.booking.infrastructure.http.MembershipEligibilityClient;
import com.fitclub.common.http.InternalServiceClient;

/** Сборка адаптеров booking-service и его типизированные настройки. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookingConfiguration.BookingProperties.class)
class BookingConfiguration {

    /**
     * @param cancelBefore минимальное время до начала, когда пользователь ещё может отменить запись (CANCEL_BEFORE_MINUTES)
     * @param membership   клиент внутреннего API membership-service
     */
    @Validated
    @ConfigurationProperties("fitclub.booking")
    record BookingProperties(@NotNull @DefaultValue("120m") Duration cancelBefore, @NotNull @Valid MembershipClient membership) {

        record MembershipClient(
                @NotNull URI baseUrl,
                @NotNull @Size(min = 32) String serviceToken,
                @NotNull @DefaultValue("1s") Duration connectTimeout,
                @NotNull @DefaultValue("2s") Duration readTimeout,
                @Min(1) @Max(3) @DefaultValue("2") int maxAttempts,
                @NotNull @DefaultValue("200ms") Duration retryBackoff) {

            @Override
            public String toString() {
                return "MembershipClient[baseUrl=" + baseUrl + "]";
            }
        }
    }

    @Bean
    BookingPolicy bookingPolicy(BookingProperties properties) {
        return new BookingPolicy(properties.cancelBefore());
    }

    @Bean
    MembershipEligibilityReader membershipEligibilityReader(BookingProperties properties) {
        BookingProperties.MembershipClient c = properties.membership();
        return new MembershipEligibilityClient(new InternalServiceClient("membership-service", c.baseUrl(),
                c.serviceToken(), c.connectTimeout(), c.readTimeout(), c.maxAttempts(), c.retryBackoff()));
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
