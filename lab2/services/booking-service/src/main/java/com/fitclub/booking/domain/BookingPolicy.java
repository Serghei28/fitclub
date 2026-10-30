package com.fitclub.booking.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Чистые правила записи и отмены (без Spring и БД). Проверки идут в порядке, понятном пользователю:
 * занятие известно → не отменено → не началось → есть абонемент → нет повторной записи → есть место → есть посещения.
 */
public final class BookingPolicy {

    private final Duration cancelBefore;

    public BookingPolicy(Duration cancelBefore) {
        this.cancelBefore = cancelBefore;
    }

    /** Проверка возможности записи; захват места и посещения делают SessionInventory и MembershipUsage. */
    public void checkCanBook(SessionInventory session, boolean membershipValid, boolean alreadyBooked, Instant now) {
        if (session == null) {
            throw BookingErrors.sessionNotReady();
        }
        if (!session.isOpen()) {
            throw BookingErrors.sessionCancelled();
        }
        if (session.hasStartedAt(now)) {
            throw BookingErrors.alreadyStarted();
        }
        if (!membershipValid) {
            throw BookingErrors.membershipNotValid();
        }
        if (alreadyBooked) {
            throw BookingErrors.alreadyBooked();
        }
        if (session.isFull()) {
            throw BookingErrors.sessionFull();
        }
    }

    /**
     * Пользователь может отменить запись, если now не позже startsAt − cancelBefore (точное равенство разрешено).
     */
    public boolean userMayCancel(Instant sessionStartsAt, Instant now) {
        return !now.isAfter(cancellationDeadline(sessionStartsAt));
    }

    public Instant cancellationDeadline(Instant sessionStartsAt) {
        return sessionStartsAt.minus(cancelBefore);
    }

    public void checkUserMayCancel(Instant sessionStartsAt, Instant now) {
        if (!userMayCancel(sessionStartsAt, now)) {
            throw BookingErrors.cancellationWindowClosed(cancelBefore.toMinutes());
        }
    }

    public Duration cancelBefore() {
        return cancelBefore;
    }
}
