package com.fitclub.booking.infrastructure.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fitclub.booking.application.SessionProjectionHandler;
import com.fitclub.booking.application.port.BookingPorts.BookingEventPublisher;
import com.fitclub.booking.domain.Booking;
import com.fitclub.booking.domain.SessionInventory;
import com.fitclub.messaging.EventEnvelope;
import com.fitclub.messaging.EventHandler;
import com.fitclub.messaging.EventProcessor;
import com.fitclub.messaging.NonRetryableEventException;
import com.fitclub.messaging.OutboxMessage;
import com.fitclub.messaging.OutboxWriter;

/** AMQP-адаптеры booking-service: вход — события расписания, выход — события бронирования (outbox). */
final class BookingMessaging {

    private BookingMessaging() {
    }

    /** Очередь booking-service.events → проекция занятий. Идемпотентность, повторы и DLQ — EventProcessor. */
    @Component
    static class ScheduleEventsListener {

        private final EventProcessor processor;
        private final Map<String, EventHandler> handlers;

        ScheduleEventsListener(EventProcessor processor, SessionProjectionHandler projection) {
            this.processor = processor;
            this.handlers = Map.of(
                    "SessionPublished", envelope -> {
                        SessionPublishedPayload p = payload(envelope, SessionPublishedPayload.class);
                        if (p.sessionId() == null || p.startsAt() == null || p.endsAt() == null
                                || p.capacity() == null || p.capacity() < 1) {
                            throw new NonRetryableEventException("SessionPublished payload is incomplete");
                        }
                        projection.onSessionPublished(new SessionInventory.PublishedSnapshot(p.sessionId(), p.title(),
                                p.trainerId(), p.trainerDisplayName(), p.startsAt(), p.endsAt(), p.capacity(),
                                envelope.aggregateVersion()));
                    },
                    "SessionCancelled", envelope -> {
                        SessionCancelledPayload p = payload(envelope, SessionCancelledPayload.class);
                        if (p.sessionId() == null) {
                            throw new NonRetryableEventException("SessionCancelled payload is incomplete");
                        }
                        projection.onSessionCancelled(p.sessionId(), envelope.aggregateVersion(),
                                p.cancelledAt() == null ? envelope.occurredAt() : p.cancelledAt());
                    });
        }

        @RabbitListener(queues = "${fitclub.booking.events-queue}", id = "booking-schedule-events")
        void onMessage(Message message) {
            processor.process(message, handlers);
        }

        private <T> T payload(EventEnvelope envelope, Class<T> type) {
            if (envelope.schemaVersion() != 1) {
                throw new NonRetryableEventException("Unsupported schema_version " + envelope.schemaVersion());
            }
            return processor.payload(envelope, type);
        }
    }

    record SessionPublishedPayload(
            @JsonProperty("session_id") UUID sessionId,
            @JsonProperty("title") String title,
            @JsonProperty("trainer_id") UUID trainerId,
            @JsonProperty("trainer_display_name") String trainerDisplayName,
            @JsonProperty("starts_at") Instant startsAt,
            @JsonProperty("ends_at") Instant endsAt,
            @JsonProperty("capacity") Integer capacity) {
    }

    record SessionCancelledPayload(
            @JsonProperty("session_id") UUID sessionId,
            @JsonProperty("cancelled_at") Instant cancelledAt) {
    }

    /** BookingConfirmed / BookingCancelled в outbox текущей транзакции (получатель — notification-service). */
    @Component
    static class OutboxBookingEventPublisher implements BookingEventPublisher {

        private final OutboxWriter outbox;

        OutboxBookingEventPublisher(OutboxWriter outbox) {
            this.outbox = outbox;
        }

        @Override
        public void bookingConfirmed(Booking b, SessionInventory s) {
            outbox.append(new OutboxMessage("BookingConfirmed", 1, "booking.booking.confirmed", "Booking", b.id(),
                    b.version(), new BookingPayload(b.id(), s.sessionId(), b.userId(), b.membershipId(), s.title(),
                            s.startsAt(), null, null)));
        }

        @Override
        public void bookingCancelled(Booking b, SessionInventory s) {
            outbox.append(new OutboxMessage("BookingCancelled", 1, "booking.booking.cancelled", "Booking", b.id(),
                    b.version(), new BookingPayload(b.id(), s.sessionId(), b.userId(), b.membershipId(), s.title(),
                            s.startsAt(), b.cancelledAt(), b.cancelReason().name())));
        }
    }

    record BookingPayload(
            @JsonProperty("booking_id") UUID bookingId,
            @JsonProperty("session_id") UUID sessionId,
            @JsonProperty("user_id") UUID userId,
            @JsonProperty("membership_id") UUID membershipId,
            @JsonProperty("session_title") String sessionTitle,
            @JsonProperty("starts_at") Instant startsAt,
            @JsonProperty("cancelled_at") Instant cancelledAt,
            @JsonProperty("reason") String reason) {
    }
}
