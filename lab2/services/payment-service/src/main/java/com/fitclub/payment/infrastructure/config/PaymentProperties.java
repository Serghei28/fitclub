package com.fitclub.payment.infrastructure.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки payment-service, проверяются при запуске.
 *
 * @param membership            клиент внутреннего API membership-service
 * @param simulationEndpoint    включить demo-завершение POST /api/payments/{id}/simulate
 *                              (только профили local/demo/test)
 */
@Validated
@ConfigurationProperties("fitclub.payment")
public record PaymentProperties(@NotNull @Valid MembershipClient membership,
        @DefaultValue("false") boolean simulationEndpoint) {

    public record MembershipClient(
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
