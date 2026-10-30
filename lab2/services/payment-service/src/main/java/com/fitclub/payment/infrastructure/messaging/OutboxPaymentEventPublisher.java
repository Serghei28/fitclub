package com.fitclub.payment.infrastructure.messaging;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.stereotype.Component;

import com.fitclub.messaging.OutboxMessage;
import com.fitclub.messaging.OutboxWriter;
import com.fitclub.payment.application.port.PaymentPorts.PaymentEventPublisher;
import com.fitclub.payment.domain.Payment;

/** PaymentSucceeded / PaymentDeclined в outbox текущей транзакции. Payload — контракт docs/events.md. */
@Component
class OutboxPaymentEventPublisher implements PaymentEventPublisher {

    private final OutboxWriter outbox;

    OutboxPaymentEventPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    @Override
    public void paymentSucceeded(Payment p) {
        outbox.append(new OutboxMessage("PaymentSucceeded", 1, "payment.payment.succeeded", "Payment", p.id(),
                p.version(), PaymentResultPayload.of(p)));
    }

    @Override
    public void paymentDeclined(Payment p) {
        outbox.append(new OutboxMessage("PaymentDeclined", 1, "payment.payment.declined", "Payment", p.id(),
                p.version(), PaymentResultPayload.of(p)));
    }

    record PaymentResultPayload(
            @JsonProperty("payment_id") UUID paymentId,
            @JsonProperty("order_id") UUID orderId,
            @JsonProperty("user_id") UUID userId,
            @JsonProperty("amount_minor") long amountMinor,
            @JsonProperty("currency") String currency,
            @JsonProperty("completed_at") Instant completedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("reason") String reason) {

        static PaymentResultPayload of(Payment p) {
            return new PaymentResultPayload(p.id(), p.membershipOrderId(), p.userId(), p.amount().minor(),
                    p.amount().currency(), p.completedAt(), p.declineReason());
        }
    }
}
