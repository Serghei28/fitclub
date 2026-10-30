package com.fitclub.payment.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Платёж по заказу абонемента. Один заказ — не более одного платежа (uq_payments_order в БД).
 * Переход из PENDING выполняется один раз; повтор того же исхода — не ошибка и не новое событие,
 * противоположный исход для завершённого платежа — конфликт.
 */
public final class Payment {

    private final UUID id;
    private final UUID membershipOrderId;
    private final UUID userId;
    private final Amount amount;
    private PaymentStatus status;
    private final String provider;
    private final String providerReference;
    private String declineReason;
    private final Instant createdAt;
    private Instant completedAt;
    private long version;

    private Payment(UUID id, UUID membershipOrderId, UUID userId, Amount amount, PaymentStatus status, String provider,
            String providerReference, String declineReason, Instant createdAt, Instant completedAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.membershipOrderId = Objects.requireNonNull(membershipOrderId);
        this.userId = Objects.requireNonNull(userId);
        this.amount = Objects.requireNonNull(amount);
        this.status = status;
        this.provider = provider;
        this.providerReference = providerReference;
        this.declineReason = declineReason;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.version = version;
    }

    public static Payment start(UUID id, UUID membershipOrderId, UUID userId, Amount amount, String provider,
            String providerReference, Instant now) {
        return new Payment(id, membershipOrderId, userId, amount, PaymentStatus.PENDING, provider, providerReference,
                null, now, null, 0);
    }

    public static Payment restore(UUID id, UUID membershipOrderId, UUID userId, Amount amount, PaymentStatus status,
            String provider, String providerReference, String declineReason, Instant createdAt, Instant completedAt,
            long version) {
        return new Payment(id, membershipOrderId, userId, amount, status, provider, providerReference, declineReason,
                createdAt, completedAt, version);
    }

    public enum CompletionOutcome {
        /** Статус изменился: нужно событие PaymentSucceeded/PaymentDeclined. */
        COMPLETED,
        /** Платёж уже был в этом статусе: ничего не меняется, событие не нужно. */
        ALREADY_IN_STATE
    }

    public CompletionOutcome succeed(Instant now) {
        return complete(PaymentStatus.SUCCEEDED, null, now);
    }

    public CompletionOutcome decline(String reason, Instant now) {
        return complete(PaymentStatus.DECLINED, reason, now);
    }

    private CompletionOutcome complete(PaymentStatus target, String reason, Instant now) {
        if (status == target) {
            return CompletionOutcome.ALREADY_IN_STATE;
        }
        if (status.terminal()) {
            throw PaymentErrors.alreadyCompleted(status);
        }
        status = target;
        declineReason = reason;
        completedAt = now;
        version++;
        return CompletionOutcome.COMPLETED;
    }

    public boolean isOwnedBy(UUID candidate) {
        return userId.equals(candidate);
    }

    public UUID id() {
        return id;
    }

    public UUID membershipOrderId() {
        return membershipOrderId;
    }

    public UUID userId() {
        return userId;
    }

    public Amount amount() {
        return amount;
    }

    public PaymentStatus status() {
        return status;
    }

    public String provider() {
        return provider;
    }

    public String providerReference() {
        return providerReference;
    }

    public String declineReason() {
        return declineReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public long version() {
        return version;
    }
}
