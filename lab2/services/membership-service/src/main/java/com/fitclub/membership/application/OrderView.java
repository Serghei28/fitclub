package com.fitclub.membership.application;

import java.time.Instant;
import java.util.UUID;

import com.fitclub.membership.domain.MembershipOrder;
import com.fitclub.membership.domain.OrderStatus;

/**
 * Представление заказа для ответа API. Сохраняется как результат идемпотентной операции,
 * поэтому повтор запроса с тем же Idempotency-Key возвращает ровно этот снимок.
 */
public record OrderView(UUID id, UUID planId, String planName, int durationDays, Integer visitLimit, long priceMinor,
        String currency, OrderStatus status, UUID paymentId, Instant paidAt, Instant failedAt, Instant createdAt) {

    public static OrderView from(MembershipOrder order) {
        return new OrderView(order.id(), order.plan().planId(), order.plan().planName(), order.plan().durationDays(),
                order.plan().visitLimit(), order.plan().price().amountMinor(), order.plan().price().currency(),
                order.status(), order.paymentId(), order.paidAt(), order.failedAt(), order.createdAt());
    }
}
