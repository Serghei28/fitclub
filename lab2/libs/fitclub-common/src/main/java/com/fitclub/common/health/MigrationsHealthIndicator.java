package com.fitclub.common.health;

import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Часть /ready: схема собственной БД должна быть доступна и полностью мигрирована.
 * Запрос к flyway_schema_history выполняется при каждой проверке, поэтому потеря БД
 * сразу переводит индикатор в DOWN.
 */
public class MigrationsHealthIndicator extends AbstractHealthIndicator {

    private final Flyway flyway;

    public MigrationsHealthIndicator(Flyway flyway) {
        super("Database migrations check failed");
        this.flyway = flyway;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        MigrationInfoService info = flyway.info();
        long pending = info.pending().length;
        boolean failed = Arrays.stream(info.all()).map(MigrationInfo::getState).anyMatch(MigrationState::isFailed);
        if (pending == 0 && !failed) {
            builder.up();
        } else {
            builder.down();
        }
    }
}
