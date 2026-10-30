package com.fitclub.schedule.infrastructure.config;

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

/** Настройки schedule-service, проверяются при запуске. */
@Validated
@ConfigurationProperties("fitclub.schedule")
public record ScheduleProperties(@NotNull @Valid AuthClient auth) {

    /**
     * Клиент внутреннего API auth-service.
     *
     * @param baseUrl        адрес auth-service (localhost или имя контейнера)
     * @param serviceToken   секрет направления schedule → auth
     * @param connectTimeout таймаут соединения
     * @param readTimeout    таймаут ожидания ответа
     * @param maxAttempts    попыток для безопасного GET (1 = без повтора)
     * @param retryBackoff   пауза между попытками
     */
    public record AuthClient(
            @NotNull URI baseUrl,
            @NotNull @Size(min = 32) String serviceToken,
            @NotNull @DefaultValue("1s") Duration connectTimeout,
            @NotNull @DefaultValue("2s") Duration readTimeout,
            @Min(1) @Max(3) @DefaultValue("2") int maxAttempts,
            @NotNull @DefaultValue("200ms") Duration retryBackoff) {

        @Override
        public String toString() {
            return "AuthClient[baseUrl=" + baseUrl + "]";
        }
    }
}
