package com.fitclub.membership.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Заказ абонемента. Клиент выбирает только тариф; цена, срок и лимит — снимок тарифа на момент заказа.
 * Переходы статуса выполняет обработка событий оплаты (шаг 7).
 */
public final class MembershipOrder {

    private final UUID id;
    private final UUID userId;
    private final PlanSnapshot plan;
    private OrderStatus status;
    private UUID paymentId;
    private Instant paidAt;
    private Instant failedAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private MembershipOrder(UUID id, UUID userId, PlanSnapshot plan, OrderStatus status, UUID paymentId, Instant paidAt,
            Instant failedAt, Instant createdAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.plan = Objects.requireNonNull(plan);
        this.status = status;
        this.paymentId = paymentId;
        this.paidAt = paidAt;
        this.failedAt = failedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static MembershipOrder place(UUID id, UUID userId, Plan plan, Instant now) {
        if (!plan.active()) {
            throw MembershipErrors.planNotAvailable();
        }
        return new MembershipOrder(id, userId, plan.snapshot(), OrderStatus.AWAITING_PAYMENT, null, null, null, now,
                now, 0);
    }

    public static MembershipOrder restore(UUID id, UUID userId, PlanSnapshot plan, OrderStatus status, UUID paymentId,
            Instant paidAt, Instant failedAt, Instant createdAt, Instant updatedAt, long version) {
        return new MembershipOrder(id, userId, plan, status, paymentId, paidAt, failedAt, createdAt, updatedAt,
                version);
    }

    public boolean isOwnedBy(UUID candidate) {
        return userId.equals(candidate);
    }

    public boolean awaitingPayment() {
        return status == OrderStatus.AWAITING_PAYMENT;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public PlanSnapshot plan() {
        return plan;
    }

    public OrderStatus status() {
        return status;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public Instant paidAt() {
        return paidAt;
    }

    public Instant failedAt() {
        return failedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
