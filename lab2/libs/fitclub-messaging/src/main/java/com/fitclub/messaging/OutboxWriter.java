package com.fitclub.messaging;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.fitclub.common.web.RequestIds;

/**
 * Запись события в таблицу outbox_events. Работает ТОЛЬКО внутри уже открытой транзакции:
 * бизнес-изменение и событие фиксируются одним commit PostgreSQL или не фиксируются вовсе.
 * Публикацию в RabbitMQ выполняет отдельно OutboxRelay.
 */
public class OutboxWriter {

    /** MDC-ключи контекста события, которое сейчас обрабатывается (для событий-следствий). */
    public static final String MDC_CORRELATION_ID = "correlation_id";
    public static final String MDC_EVENT_ID = "event_id";

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final String producer;

    public OutboxWriter(JdbcTemplate jdbc, JsonMapper jsonMapper, Clock clock, String producer) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.producer = producer;
    }

    public EventEnvelope append(OutboxMessage message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox event must be written inside the business transaction");
        }
        Instant now = clock.instant();
        String correlationId = firstNonNull(MDC.get(MDC_CORRELATION_ID), MDC.get(RequestIds.MDC_KEY),
                UUID.randomUUID().toString());
        // Событие из HTTP-запроса вызвано самим запросом; событие-следствие — обработанным событием
        String causationId = firstNonNull(MDC.get(MDC_EVENT_ID), correlationId);
        JsonNode payload = jsonMapper.valueToTree(message.payload());
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), message.eventType(), message.schemaVersion(),
                now, producer, message.aggregateId(), message.aggregateVersion(), correlationId, causationId, payload);

        jdbc.update("""
                insert into outbox_events (id, event_type, routing_key, aggregate_type, aggregate_id,
                    aggregate_version, envelope, occurred_at, attempts, next_attempt_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 0, ?)
                """,
                envelope.eventId(), message.eventType(), message.routingKey(), message.aggregateType(),
                message.aggregateId(), message.aggregateVersion(), jsonMapper.writeValueAsString(envelope),
                Timestamp.from(now), Timestamp.from(now));
        return envelope;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
