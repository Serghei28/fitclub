package com.fitclub.membership.infrastructure.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fitclub.membership.application.PaymentResultHandler;
import com.fitclub.membership.application.PaymentResultHandler.Outcome;
import com.fitclub.membership.application.PaymentResultHandler.PaymentResult;
import com.fitclub.messaging.EventEnvelope;
import com.fitclub.messaging.EventHandler;
import com.fitclub.messaging.EventProcessor;
import com.fitclub.messaging.NonRetryableEventException;

/**
 * AMQP-адаптер: очередь membership-service.events → PaymentResultHandler.
 * Транспортная часть (идемпотентность processed_events, повторы, DLQ) — в EventProcessor;
 * здесь только разбор payload и выбор сценария. Отклонённое бизнес-правилом событие уходит в DLQ.
 */
@Component
class PaymentEventsListener {

    private final EventProcessor processor;
    private final Map<String, EventHandler> handlers;

    PaymentEventsListener(EventProcessor processor, PaymentResultHandler paymentResults) {
        this.processor = processor;
        this.handlers = Map.of(
                "PaymentSucceeded", envelope -> requireApplied(paymentResults.onPaymentSucceeded(result(envelope))),
                "PaymentDeclined", envelope -> requireApplied(paymentResults.onPaymentDeclined(result(envelope))));
    }

    @RabbitListener(queues = "${fitclub.membership.events-queue}", id = "membership-payment-events")
    void onMessage(Message message) {
        processor.process(message, handlers);
    }

    private PaymentResult result(EventEnvelope envelope) {
        if (envelope.schemaVersion() != 1) {
            throw new NonRetryableEventException("Unsupported schema_version " + envelope.schemaVersion());
        }
        PaymentResultPayload p = processor.payload(envelope, PaymentResultPayload.class);
        if (p.paymentId() == null || p.orderId() == null || p.userId() == null || p.amountMinor() == null
                || p.currency() == null || p.completedAt() == null) {
            throw new NonRetryableEventException("Payment result payload misses required fields");
        }
        return new PaymentResult(p.paymentId(), p.orderId(), p.userId(), p.amountMinor(), p.currency(),
                p.completedAt());
    }

    private static void requireApplied(Outcome outcome) {
        if (outcome == Outcome.REJECTED) {
            throw new NonRetryableEventException("Payment result contradicts membership data");
        }
    }

    record PaymentResultPayload(
            @JsonProperty("payment_id") UUID paymentId,
            @JsonProperty("order_id") UUID orderId,
            @JsonProperty("user_id") UUID userId,
            @JsonProperty("amount_minor") Long amountMinor,
            @JsonProperty("currency") String currency,
            @JsonProperty("completed_at") Instant completedAt) {
    }
}
