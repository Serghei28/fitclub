package com.fitclub.booking.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.fitclub.booking.domain.Booking;
import com.fitclub.booking.domain.SessionInventory;

/**
 * JPA-отображения booking_db. Оптимистичный @Version здесь не нужен: все изменения выполняются под
 * пессимистичной блокировкой строки (SELECT … FOR UPDATE); колонки version/aggregate_version — версии
 * агрегатов для событий, ими управляет домен.
 */
final class BookingEntities {

    private BookingEntities() {
    }

    @Entity(name = "InventoryEntity")
    @Table(name = "session_inventory")
    static class InventoryEntity {
        @Id
        @Column(name = "session_id")
        UUID sessionId;
        @Column(length = 150)
        String title;
        @Column(name = "trainer_id")
        UUID trainerId;
        @Column(name = "trainer_display_name", length = 100)
        String trainerDisplayName;
        @Column(name = "starts_at")
        Instant startsAt;
        @Column(name = "ends_at")
        Instant endsAt;
        Integer capacity;
        @Column(name = "booked_count", nullable = false)
        int bookedCount;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 16)
        SessionInventory.Status status;
        @Column(name = "aggregate_version", nullable = false)
        long aggregateVersion;
        @Column(name = "cancelled_at")
        Instant cancelledAt;
        @Column(name = "updated_at", nullable = false)
        Instant updatedAt;
    }

    @Entity(name = "BookingEntity")
    @Table(name = "bookings")
    static class BookingEntity {
        @Id
        UUID id;
        @Column(name = "session_id", nullable = false)
        UUID sessionId;
        @Column(name = "user_id", nullable = false)
        UUID userId;
        @Column(name = "user_display_name", length = 100)
        String userDisplayName;
        @Column(name = "membership_id", nullable = false)
        UUID membershipId;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 16)
        Booking.Status status;
        @Enumerated(EnumType.STRING)
        @Column(name = "cancel_reason", length = 32)
        Booking.CancelReason cancelReason;
        @Column(name = "created_at", nullable = false)
        Instant createdAt;
        @Column(name = "cancelled_at")
        Instant cancelledAt;
        @Column(nullable = false)
        long version;
    }

    @Entity(name = "UsageEntity")
    @Table(name = "membership_usage")
    static class UsageEntity {
        @Id
        @Column(name = "membership_id")
        UUID membershipId;
        @Column(name = "user_id", nullable = false)
        UUID userId;
        @Column(name = "visit_limit")
        Integer visitLimit;
        @Column(name = "used_count", nullable = false)
        int usedCount;
        @Column(name = "updated_at", nullable = false)
        Instant updatedAt;
    }
}
