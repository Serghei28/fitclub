package com.fitclub.messaging;

import java.sql.Timestamp;
import java.time.Clock;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Состояние доставки событий: DOWN, если есть события, не отправленные дольше staleAfter.
 * Входит в группу messaging (/actuator/health/messaging), но НЕ в /ready: без брокера сервис продолжает
 * безопасно принимать операции и копить события в outbox — это деградация, а не неготовность HTTP.
 */
public class OutboxHealthIndicator extends AbstractHealthIndicator {

    private final JdbcTemplate jdbc;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxHealthIndicator(JdbcTemplate jdbc, OutboxProperties properties, Clock clock) {
        super("Outbox check failed");
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Integer stale = jdbc.queryForObject(
                "select count(*) from outbox_events where published_at is null and occurred_at < ?", Integer.class,
                Timestamp.from(clock.instant().minus(properties.staleAfter())));
        if (stale == null || stale == 0) {
            builder.up();
        } else {
            builder.down();
        }
    }
}
