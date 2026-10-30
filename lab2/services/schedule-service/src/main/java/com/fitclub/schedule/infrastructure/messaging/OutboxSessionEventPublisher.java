package com.fitclub.schedule.infrastructure.messaging;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.stereotype.Component;

import com.fitclub.messaging.OutboxMessage;
import com.fitclub.messaging.OutboxWriter;
import com.fitclub.schedule.application.port.SessionEventPublisher;
import com.fitclub.schedule.domain.Session;

/**
 * Адаптер порта событий: SessionPublished / SessionCancelled записываются в outbox в текущей транзакции.
 * SessionPublished несёт полный снимок занятия — booking-service строит по нему свою проекцию
 * (session_inventory) и не обращается к Schedule при записи на занятие.
 */
@Component
class OutboxSessionEventPublisher implements SessionEventPublisher {

    static final String SESSION_PUBLISHED = "SessionPublished";
    static final String SESSION_CANCELLED = "SessionCancelled";

    private final OutboxWriter outbox;

    OutboxSessionEventPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    @Override
    public void sessionPublished(Session s) {
        outbox.append(new OutboxMessage(SESSION_PUBLISHED, 1, "schedule.session.published", "Session", s.id(),
                s.version(), new SessionPublishedPayload(s.id(), s.trainingTypeId(), s.title(), s.trainerId(),
                        s.trainerDisplayName(), s.startsAt(), s.endsAt(), s.capacity())));
    }

    @Override
    public void sessionCancelled(Session s) {
        outbox.append(new OutboxMessage(SESSION_CANCELLED, 1, "schedule.session.cancelled", "Session", s.id(),
                s.version(), new SessionCancelledPayload(s.id(), s.startsAt(), s.cancelledAt(), s.cancelReason())));
    }

    record SessionPublishedPayload(
            @JsonProperty("session_id") UUID sessionId,
            @JsonProperty("training_type_id") UUID trainingTypeId,
            @JsonProperty("title") String title,
            @JsonProperty("trainer_id") UUID trainerId,
            @JsonProperty("trainer_display_name") String trainerDisplayName,
            @JsonProperty("starts_at") Instant startsAt,
            @JsonProperty("ends_at") Instant endsAt,
            @JsonProperty("capacity") int capacity) {
    }

    record SessionCancelledPayload(
            @JsonProperty("session_id") UUID sessionId,
            @JsonProperty("starts_at") Instant startsAt,
            @JsonProperty("cancelled_at") Instant cancelledAt,
            @JsonProperty("reason") String reason) {
    }
}
