package com.fitclub.messaging;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Фоновая публикация outbox_events в RabbitMQ.
 * <ol>
 *   <li>в короткой транзакции берёт пачку неотправленных событий (FOR UPDATE SKIP LOCKED — параллельные
 *       экземпляры не берут одни и те же строки; блокируются только строки outbox, не бизнес-таблицы);</li>
 *   <li>отправляет persistent-сообщение с mandatory=true и ждёт publisher confirm;</li>
 *   <li>помечает событие отправленным, только если брокер подтвердил приём И сообщение не вернулось
 *       как unroutable (confirm сам по себе не доказывает, что есть очередь-получатель);</li>
 *   <li>иначе увеличивает attempts и откладывает повтор с экспоненциальной паузой.</li>
 * </ol>
 * Сбой между отправкой и отметкой в БД приведёт к повторной отправке с тем же event_id — поэтому
 * потребители обязаны быть идемпотентными. Exactly-once не обещается.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RabbitTemplate rabbit;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate transaction, RabbitTemplate rabbit,
            OutboxProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.rabbit = rabbit;
        this.properties = properties;
        this.clock = clock;
    }

    record PendingEvent(UUID id, String eventType, String routingKey, String envelope, int attempts,
            String correlationId) {
    }

    @Scheduled(fixedDelayString = "${fitclub.outbox.poll-interval:1s}", initialDelayString = "2s")
    public void publishPending() {
        if (properties.relayEnabled()) {
            publishBatch();
        }
    }

    /** Один проход relay; возвращает число успешно отправленных событий. */
    public int publishBatch() {
        Integer published = transaction.execute(status -> {
            List<PendingEvent> batch = jdbc.query("""
                    select id, event_type, routing_key, envelope::text as envelope, attempts,
                           envelope ->> 'correlation_id' as correlation_id
                    from outbox_events
                    where published_at is null and next_attempt_at <= ?
                    order by occurred_at
                    limit ?
                    for update skip locked
                    """,
                    (rs, i) -> new PendingEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
                            rs.getString("routing_key"), rs.getString("envelope"), rs.getInt("attempts"),
                            rs.getString("correlation_id")),
                    Timestamp.from(clock.instant()), properties.batchSize());
            int ok = 0;
            for (PendingEvent event : batch) {
                String failure = send(event);
                if (failure == null) {
                    markPublished(event);
                    ok++;
                } else {
                    markFailed(event, failure);
                    if (failure.startsWith("broker unavailable")) {
                        // Брокер недоступен: остальные события пачки подождут следующего прохода
                        break;
                    }
                }
            }
            return ok;
        });
        return published == null ? 0 : published;
    }

    private String send(PendingEvent event) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(event.id().toString());
        props.setType(event.eventType());
        props.setCorrelationId(event.correlationId());
        props.setTimestamp(Date.from(clock.instant()));
        Message message = MessageBuilder.withBody(event.envelope().getBytes(StandardCharsets.UTF_8))
                .andProperties(props).build();
        CorrelationData correlation = new CorrelationData(event.id().toString());
        try {
            rabbit.send(properties.exchange(), event.routingKey(), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                return "nack: " + confirm.getReason();
            }
            if (correlation.getReturned() != null) {
                return "unroutable: " + correlation.getReturned().getReplyText();
            }
            return null;
        } catch (AmqpConnectException e) {
            return "broker unavailable: " + (e.getCause() == null ? e : e.getCause()).getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "broker unavailable: interrupted";
        } catch (Exception e) {
            return "broker unavailable: " + e.getClass().getSimpleName();
        }
    }

    private void markPublished(PendingEvent event) {
        jdbc.update("update outbox_events set published_at = ?, attempts = attempts + 1, last_error = null "
                + "where id = ?", Timestamp.from(clock.instant()), event.id());
        withEventMdc(event, () -> log.info("Event published: {}", event.eventType()));
    }

    private void markFailed(PendingEvent event, String reason) {
        int attempts = event.attempts() + 1;
        Instant next = clock.instant().plus(properties.backoffAfter(attempts));
        String truncated = reason.length() > 500 ? reason.substring(0, 500) : reason;
        jdbc.update("update outbox_events set attempts = ?, next_attempt_at = ?, last_error = ? where id = ?",
                attempts, Timestamp.from(next), truncated, event.id());
        withEventMdc(event, () -> log.warn("Event publish failed (attempt {}), retry at {}: {}", attempts, next,
                truncated));
    }

    private static void withEventMdc(PendingEvent event, Runnable logging) {
        try {
            MDC.put("event_id", event.id().toString());
            MDC.put("event_type", event.eventType());
            MDC.put("correlation_id", String.valueOf(event.correlationId()));
            logging.run();
        } finally {
            MDC.remove("event_id");
            MDC.remove("event_type");
            MDC.remove("correlation_id");
        }
    }
}
