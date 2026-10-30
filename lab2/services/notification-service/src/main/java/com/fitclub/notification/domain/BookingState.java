package com.fitclub.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Проекция последнего известного состояния бронирования (booking_projection) с версией агрегата.
 * Событие применяется только с версией больше известной: отменённое бронирование остаётся «надгробием»,
 * и опоздавшее BookingConfirmed не создаст ни подтверждения, ни напоминания.
 */
public record BookingState(UUID bookingId, UUID userId, UUID sessionId, String sessionTitle, Instant startsAt,
        Status status, long version) {

    public enum Status { CONFIRMED, CANCELLED }

    public BookingState {
        Objects.requireNonNull(bookingId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(status);
    }

    /** Новое событие устарело относительно известного состояния? */
    public static boolean isStale(BookingState known, long eventVersion) {
        return known != null && eventVersion <= known.version();
    }
}
