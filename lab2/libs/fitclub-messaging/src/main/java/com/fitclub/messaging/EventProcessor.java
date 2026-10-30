package com.fitclub.messaging;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Идемпотентная обработка входящего события.
 * <ol>
 *   <li>разбор конверта; испорченное сообщение → сразу DLQ;</li>
 *   <li>одна локальная транзакция: INSERT processed_events(event_id) ON CONFLICT DO NOTHING; если строка уже была —
 *       это повторная доставка, обработка пропускается; иначе вызывается обработчик;</li>
 *   <li>commit, после чего контейнер отправляет брокеру ACK (acknowledge-mode=auto: ACK после возврата из метода);</li>
 *   <li>временная ошибка (БД и т.п.) — ограниченные повторы с паузой внутри процесса, затем reject без requeue → DLQ;
 *       NonRetryableEventException → DLQ сразу. Бесконечного «горячего» requeue нет; страховка — x-delivery-limit.</li>
 * </ol>
 * Гарантия — at-least-once доставка + идемпотентная обработка, а не exactly-once.
 */
public class EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration backoff;

    public EventProcessor(JsonMapper jsonMapper, JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock,
            int maxAttempts, Duration backoff) {
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = backoff;
    }

    public void process(Message message, Map<String, EventHandler> handlers) {
        EventEnvelope envelope = parse(message);
        EventHandler handler = handlers.get(envelope.eventType());
        try {
            MDC.put("event_id", envelope.eventId().toString());
            MDC.put("event_type", envelope.eventType());
            MDC.put("correlation_id", String.valueOf(envelope.correlationId()));
            MDC.put("causation_id", String.valueOf(envelope.causationId()));
            if (handler == null) {
                log.error("No handler for event type, sending to DLQ");
                throw new AmqpRejectAndDontRequeueException("Unsupported event type " + envelope.eventType());
            }
            processWithRetries(envelope, handler);
        } finally {
            MDC.remove("event_id");
            MDC.remove("event_type");
            MDC.remove("correlation_id");
            MDC.remove("causation_id");
        }
    }

    private void processWithRetries(EventEnvelope envelope, EventHandler handler) {
        for (int attempt = 1; ; attempt++) {
            try {
                Boolean fresh = transaction.execute(status -> {
                    int inserted = jdbc.update("""
                            insert into processed_events (event_id, event_type, processed_at) values (?, ?, ?)
                            on conflict (event_id) do nothing
                            """, envelope.eventId(), envelope.eventType(), Timestamp.from(clock.instant()));
                    if (inserted == 0) {
                        return false;
                    }
                    handler.handle(envelope);
                    return true;
                });
                if (Boolean.TRUE.equals(fresh)) {
                    log.info("Event processed");
                } else {
                    log.info("Duplicate event skipped");
                }
                return;
            } catch (NonRetryableEventException e) {
                log.error("Event rejected permanently, sending to DLQ: {}", e.getMessage());
                throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
            } catch (RuntimeException e) {
                if (attempt >= maxAttempts) {
                    log.error("Event processing failed after {} attempt(s), sending to DLQ", attempt, e);
                    throw new AmqpRejectAndDontRequeueException("Retries exhausted", e);
                }
                log.warn("Event processing failed (attempt {}/{}), retrying: {}", attempt, maxAttempts,
                        e.getClass().getSimpleName());
                sleep(backoff.multipliedBy(attempt));
            }
        }
    }

    private EventEnvelope parse(Message message) {
        try {
            EventEnvelope envelope = jsonMapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8),
                    EventEnvelope.class);
            if (envelope.eventId() == null || envelope.eventType() == null || envelope.payload() == null) {
                throw new AmqpRejectAndDontRequeueException("Envelope misses required fields");
            }
            return envelope;
        } catch (JacksonException e) {
            log.error("Malformed event message, sending to DLQ: message_id={}",
                    message.getMessageProperties().getMessageId());
            throw new AmqpRejectAndDontRequeueException("Malformed event", e);
        }
    }

    /** Payload события в типизированный record обработчика; несовместимый payload — неисправимая ошибка. */
    public <T> T payload(EventEnvelope envelope, Class<T> type) {
        try {
            return jsonMapper.treeToValue(envelope.payload(), type);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new NonRetryableEventException("Payload does not match " + type.getSimpleName(), e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
