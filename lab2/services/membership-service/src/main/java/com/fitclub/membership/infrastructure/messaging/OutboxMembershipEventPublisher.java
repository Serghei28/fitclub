package com.fitclub.membership.infrastructure.messaging;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.stereotype.Component;

import com.fitclub.membership.application.port.MembershipPorts.MembershipEventPublisher;
import com.fitclub.membership.domain.Membership;
import com.fitclub.messaging.OutboxMessage;
import com.fitclub.messaging.OutboxWriter;

/** MembershipActivated в outbox текущей транзакции (получатель — notification-service). */
@Component
class OutboxMembershipEventPublisher implements MembershipEventPublisher {

    private final OutboxWriter outbox;

    OutboxMembershipEventPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    @Override
    public void membershipActivated(Membership m) {
        outbox.append(new OutboxMessage("MembershipActivated", 1, "membership.membership.activated", "Membership",
                m.id(), 1, new MembershipActivatedPayload(m.id(), m.orderId(), m.userId(), m.planName(), m.startsAt(),
                        m.endsAt(), m.visitLimit())));
    }

    record MembershipActivatedPayload(
            @JsonProperty("membership_id") UUID membershipId,
            @JsonProperty("order_id") UUID orderId,
            @JsonProperty("user_id") UUID userId,
            @JsonProperty("plan_name") String planName,
            @JsonProperty("starts_at") Instant startsAt,
            @JsonProperty("ends_at") Instant endsAt,
            // null = безлимит; поле присутствует всегда
            @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("visit_limit") Integer visitLimit) {
    }
}
