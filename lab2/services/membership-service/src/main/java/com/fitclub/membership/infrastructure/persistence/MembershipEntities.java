package com.fitclub.membership.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fitclub.membership.domain.OrderStatus;

/**
 * JPA-отображения таблиц membership_db. Используются только адаптерами этого пакета.
 * Имена сущностей заданы явно: у вложенных классов имя по умолчанию содержит «$».
 */
final class MembershipEntities {

    private MembershipEntities() {
    }

    @Entity(name = "PlanEntity")
    @Table(name = "plans")
    static class PlanEntity {
        @Id
        UUID id;
        @Column(nullable = false, length = 100)
        String name;
        @Column(name = "duration_days", nullable = false)
        int durationDays;
        @Column(name = "visit_limit")
        Integer visitLimit;
        @Column(name = "price_minor", nullable = false)
        long priceMinor;
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(nullable = false, length = 3)
        String currency;
        @Column(nullable = false)
        boolean active;
        @Column(name = "created_at", nullable = false)
        Instant createdAt;
        @Column(name = "updated_at", nullable = false)
        Instant updatedAt;
        @Version
        long version;
    }

    @Entity(name = "OrderEntity")
    @Table(name = "membership_orders")
    static class OrderEntity {
        @Id
        UUID id;
        @Column(name = "user_id", nullable = false)
        UUID userId;
        @Column(name = "plan_id", nullable = false)
        UUID planId;
        @Column(name = "plan_name", nullable = false, length = 100)
        String planName;
        @Column(name = "duration_days", nullable = false)
        int durationDays;
        @Column(name = "visit_limit")
        Integer visitLimit;
        @Column(name = "price_minor", nullable = false)
        long priceMinor;
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(nullable = false, length = 3)
        String currency;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        OrderStatus status;
        @Column(name = "payment_id")
        UUID paymentId;
        @Column(name = "paid_at")
        Instant paidAt;
        @Column(name = "failed_at")
        Instant failedAt;
        @Column(name = "created_at", nullable = false)
        Instant createdAt;
        @Column(name = "updated_at", nullable = false)
        Instant updatedAt;
        @Version
        long version;
    }

    @Entity(name = "MembershipEntity")
    @Table(name = "memberships")
    static class MembershipEntity {
        @Id
        UUID id;
        @Column(name = "user_id", nullable = false)
        UUID userId;
        @Column(name = "order_id", nullable = false)
        UUID orderId;
        @Column(name = "payment_id", nullable = false)
        UUID paymentId;
        @Column(name = "plan_name", nullable = false, length = 100)
        String planName;
        @Column(name = "visit_limit")
        Integer visitLimit;
        @Column(name = "starts_at", nullable = false)
        Instant startsAt;
        @Column(name = "ends_at", nullable = false)
        Instant endsAt;
        @Column(name = "created_at", nullable = false)
        Instant createdAt;
    }
}
