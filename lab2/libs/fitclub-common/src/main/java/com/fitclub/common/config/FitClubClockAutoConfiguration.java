package com.fitclub.common.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

import com.fitclub.common.idempotency.IdempotencyStore;

/**
 * Единый источник времени (UTC) и хранилище Idempotency-Key. Бизнес-код получает Clock через конструктор,
 * поэтому тесты подменяют время фиксированными часами, а не ждут реального момента.
 */
@AutoConfiguration(afterName = {"org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"})
public class FitClubClockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Idempotency-Key для сервисов, у которых есть таблица idempotency_records. */
    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    IdempotencyStore idempotencyStore(JdbcTemplate jdbc, JsonMapper jsonMapper, Clock clock) {
        return new IdempotencyStore(jdbc, jsonMapper, clock);
    }
}
