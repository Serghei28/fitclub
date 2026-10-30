package com.fitclub.booking.application;

import java.time.Instant;
import java.util.UUID;

import com.fitclub.booking.domain.Booking;
import com.fitclub.booking.domain.SessionInventory;

/** Запись вместе со снимком занятия; сохраняется как результат идемпотентного создания. */
public record BookingView(UUID id, UUID sessionId, String sessionTitle, String trainerDisplayName, Instant startsAt,
        Instant endsAt, UUID membershipId, Booking.Status status, Booking.CancelReason cancelReason, Instant createdAt,
        Instant cancelledAt) {

    public static BookingView of(Booking b, SessionInventory s) {
        return new BookingView(b.id(), b.sessionId(), s == null ? null : s.title(),
                s == null ? null : s.trainerDisplayName(), s == null ? null : s.startsAt(),
                s == null ? null : s.endsAt(), b.membershipId(), b.status(), b.cancelReason(), b.createdAt(),
                b.cancelledAt());
    }
}
