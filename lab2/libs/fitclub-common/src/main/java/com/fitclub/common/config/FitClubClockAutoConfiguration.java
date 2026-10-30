package com.fitclub.common.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Единый источник времени (UTC). Бизнес-код получает Clock через конструктор,
 * поэтому тесты подменяют время фиксированными часами, а не ждут реального момента.
 */
@AutoConfiguration
public class FitClubClockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
