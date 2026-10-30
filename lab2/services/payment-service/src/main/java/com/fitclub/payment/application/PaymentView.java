package com.fitclub.payment.application;

import java.time.Instant;
import java.util.UUID;

import com.fitclub.payment.domain.Payment;
import com.fitclub.payment.domain.PaymentStatus;

/** Представление платежа для ответа API; сохраняется как результат идемпотентных операций. */
public record PaymentView(UUID id, UUID membershipOrderId, long amountMinor, String currency, PaymentStatus status,
        String provider, String declineReason, Instant createdAt, Instant completedAt) {

    public static PaymentView from(Payment p) {
        return new PaymentView(p.id(), p.membershipOrderId(), p.amount().minor(), p.amount().currency(), p.status(),
                p.provider(), p.declineReason(), p.createdAt(), p.completedAt());
    }
}
