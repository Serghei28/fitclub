package com.fitclub.messaging;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки outbox publisher.
 *
 * @param exchange        topic exchange для всех событий
 * @param pollInterval    пауза между проходами relay
 * @param batchSize       сколько событий публикуется за один проход
 * @param confirmTimeout  сколько ждать publisher confirm от брокера
 * @param initialBackoff  пауза перед повтором после первой неудачи (далее удваивается)
 * @param maxBackoff      максимальная пауза между повторами
 * @param staleAfter      событие, не отправленное дольше этого, переводит индикатор outbox в DOWN
 * @param relayEnabled    false — только запись в outbox без публикации (для отдельных тестов)
 */
@Validated
@ConfigurationProperties("fitclub.outbox")
public record OutboxProperties(
        @NotBlank @DefaultValue("fitclub.events") String exchange,
        @NotNull @DefaultValue("1s") Duration pollInterval,
        @Min(1) @DefaultValue("50") int batchSize,
        @NotNull @DefaultValue("5s") Duration confirmTimeout,
        @NotNull @DefaultValue("2s") Duration initialBackoff,
        @NotNull @DefaultValue("5m") Duration maxBackoff,
        @NotNull @DefaultValue("60s") Duration staleAfter,
        @DefaultValue("true") boolean relayEnabled) {

    /** Пауза перед попыткой номер attempts+1: 2с, 4с, 8с … не больше maxBackoff. */
    public Duration backoffAfter(int attempts) {
        long factor = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        Duration candidate = initialBackoff.multipliedBy(factor);
        return candidate.compareTo(maxBackoff) > 0 ? maxBackoff : candidate;
    }
}
