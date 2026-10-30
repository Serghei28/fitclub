package com.fitclub.booking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Запись на занятие. CONFIRMED занимает место и единицу абонемента; прошедшие CONFIRMED — история посещений.
 * Отмена выполняется один раз: повторная отмена ничего не меняет и не возвращает ресурс второй раз.
 * version: 1 при создании (BookingConfirmed), 2 после отмены (BookingCancelled) — aggregate_version событий.
 */
public final class Booking {

    public enum Status { CONFIRMED, CANCELLED }

    public enum CancelReason { USER, SESSION_CANCELLED }

    private final UUID id;
    private final UUID sessionId;
    private final UUID userId;
    private final String userDisplayName;
    private final UUID membershipId;
    private Status status;
    private CancelReason cancelReason;
    private final Instant createdAt;
    private Instant cancelledAt;
    private long version;

    private Booking(UUID id, UUID sessionId, UUID userId, String userDisplayName, UUID membershipId, Status status,
            CancelReason cancelReason, Instant createdAt, Instant cancelledAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.userId = Objects.requireNonNull(userId);
        this.userDisplayName = userDisplayName;
        this.membershipId = Objects.requireNonNull(membershipId);
        this.status = status;
        this.cancelReason = cancelReason;
        this.createdAt = createdAt;
        this.cancelledAt = cancelledAt;
        this.version = version;
    }

    public static Booking confirm(UUID id, UUID sessionId, UUID userId, String userDisplayName, UUID membershipId,
            Instant now) {
        return new Booking(id, sessionId, userId, userDisplayName, membershipId, Status.CONFIRMED, null, now, null, 1);
    }

    public static Booking restore(UUID id, UUID sessionId, UUID userId, String userDisplayName, UUID membershipId,
            Status status, CancelReason cancelReason, Instant createdAt, Instant cancelledAt, long version) {
        return new Booking(id, sessionId, userId, userDisplayName, membershipId, status, cancelReason, createdAt,
                cancelledAt, version);
    }

    /** @return true — отменено сейчас (нужно вернуть место и посещение); false — уже было отменено. */
    public boolean cancel(CancelReason reason, Instant now) {
        if (status == Status.CANCELLED) {
            return false;
        }
        status = Status.CANCELLED;
        cancelReason = reason;
        cancelledAt = now;
        version++;
        return true;
    }

    public boolean isOwnedBy(UUID candidate) {
        return userId.equals(candidate);
    }

    public boolean isConfirmed() {
        return status == Status.CONFIRMED;
    }

    public UUID id() {
        return id;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public UUID userId() {
        return userId;
    }

    public String userDisplayName() {
        return userDisplayName;
    }

    public UUID membershipId() {
        return membershipId;
    }

    public Status status() {
        return status;
    }

    public CancelReason cancelReason() {
        return cancelReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public long version() {
        return version;
    }
}
