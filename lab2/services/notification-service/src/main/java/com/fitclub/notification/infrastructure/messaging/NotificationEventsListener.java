package com.fitclub.notification.infrastructure.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fitclub.messaging.EventEnvelope;
import com.fitclub.messaging.EventHandler;
import com.fitclub.messaging.EventProcessor;
import com.fitclub.messaging.NonRetryableEventException;
import com.fitclub.notification.application.NotificationEventHandler;
import com.fitclub.notification.application.NotificationEventHandler.BookingEvent;
import com.fitclub.notification.application.NotificationEventHandler.MembershipActivated;

/**
 * Очередь notification-service.events → NotificationEventHandler.
 * Идемпотентность (processed_events), повторы и DLQ — EventProcessor; здесь только разбор payload.
 */
@Component
class NotificationEventsListener {

    private final EventProcessor processor;
    private final Map<String, EventHandler> handlers;

    NotificationEventsListener(EventProcessor processor, NotificationEventHandler handler) {
        this.processor = processor;
        this.handlers = Map.of(
                "MembershipActivated", e -> {
                    MembershipActivatedPayload p = payload(e, MembershipActivatedPayload.class);
                    require(p.membershipId() != null && p.userId() != null && p.startsAt() != null);
                    handler.onMembershipActivated(new MembershipActivated(e.eventId(), p.membershipId(), p.userId(),
                            p.planName(), p.startsAt(), p.endsAt(), p.visitLimit()));
                },
                "BookingConfirmed", e -> handler.onBookingConfirmed(bookingEvent(e)),
                "BookingCancelled", e -> handler.onBookingCancelled(bookingEvent(e)));
    }

    @RabbitListener(queues = "${fitclub.notification.events-queue}", id = "notification-events")
    void onMessage(Message message) {
        processor.process(message, handlers);
    }

    private BookingEvent bookingEvent(EventEnvelope e) {
        BookingPayload p = payload(e, BookingPayload.class);
        require(p.bookingId() != null && p.userId() != null && p.startsAt() != null);
        return new BookingEvent(e.eventId(), e.aggregateVersion(), p.bookingId(), p.userId(), p.sessionId(),
                p.sessionTitle(), p.startsAt(), p.reason());
    }

    private <T> T payload(EventEnvelope envelope, Class<T> type) {
        if (envelope.schemaVersion() != 1) {
            throw new NonRetryableEventException("Unsupported schema_version " + envelope.schemaVersion());
        }
        return processor.payload(envelope, type);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new NonRetryableEventException("Event payload misses required fields");
        }
    }

    record MembershipActivatedPayload(
            @JsonProperty("membership_id") UUID membershipId,
            @JsonProperty("user_id") UUID userId,
            @JsonProperty("plan_name") String planName,
            @JsonProperty("starts_at") Instant startsAt,
            @JsonProperty("ends_at") Instant endsAt,
            @JsonProperty("visit_limit") Integer visitLimit) {
    }

    record BookingPayload(
            @JsonProperty("booking_id") UUID bookingId,
            @JsonProperty("session_id") UUID sessionId,
            @JsonProperty("user_id") UUID userId,
            @JsonProperty("session_title") String sessionTitle,
            @JsonProperty("starts_at") Instant startsAt,
            @JsonProperty("reason") String reason) {
    }
}
