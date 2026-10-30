package com.fitclub.common.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import com.fitclub.common.health.MigrationsHealthIndicator;

/** Индикатор migrations для группы readiness (/ready). */
@AutoConfiguration(afterName = "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@ConditionalOnClass(Flyway.class)
public class FitClubHealthAutoConfiguration {

    @Bean
    @ConditionalOnBean(Flyway.class)
    MigrationsHealthIndicator migrationsHealthIndicator(Flyway flyway) {
        return new MigrationsHealthIndicator(flyway);
    }
}
