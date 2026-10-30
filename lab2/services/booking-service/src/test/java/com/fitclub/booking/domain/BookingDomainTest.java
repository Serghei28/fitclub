package com.fitclub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fitclub.common.error.DomainException;

/** Правила Booking без Spring и БД: окно отмены, версии проекции, лимит посещений, порядок проверок. */
class BookingDomainTest {

    static final Instant START = Instant.parse("2026-11-10T07:00:00Z");
    final BookingPolicy policy = new BookingPolicy(Duration.ofMinutes(120));

    static SessionInventory open(int capacity, long version) {
        return SessionInventory.fromPublished(new SessionInventory.PublishedSnapshot(UUID.randomUUID(), "Йога",
                UUID.randomUUID(), "Ольга", START, START.plusSeconds(3600), capacity, version));
    }

    static void assertCode(Throwable e, String code) {
        assertThat(e).isInstanceOf(DomainException.class);
        assertThat(((DomainException) e).code()).isEqualTo(code);
    }

    @Test
    void cancellationWindowIncludesExactDeadlineAndRejectsOneSecondLater() {
        Instant deadline = START.minus(Duration.ofMinutes(120));
        assertThat(policy.userMayCancel(START, deadline.minusSeconds(1))).isTrue();
        assertThat(policy.userMayCancel(START, deadline)).isTrue();
        assertThat(policy.userMayCancel(START, deadline.plusSeconds(1))).isFalse();
        assertThatThrownBy(() -> policy.checkUserMayCancel(START, deadline.plusMillis(1)))
                .satisfies(e -> assertCode(e, BookingErrors.CANCELLATION_WINDOW_CLOSED));
    }

    @Test
    void bookingChecksInUserFriendlyOrder() {
        Instant before = START.minusSeconds(60);
        assertThatThrownBy(() -> policy.checkCanBook(null, true, false, before))
                .satisfies(e -> assertCode(e, BookingErrors.SESSION_NOT_READY));
        assertThatThrownBy(() -> policy.checkCanBook(open(5, 1), true, false, START))
                .satisfies(e -> assertCode(e, BookingErrors.SESSION_ALREADY_STARTED));
        assertThatThrownBy(() -> policy.checkCanBook(open(5, 1), false, false, before))
                .satisfies(e -> assertCode(e, BookingErrors.MEMBERSHIP_NOT_VALID));
        assertThatThrownBy(() -> policy.checkCanBook(open(5, 1), true, true, before))
                .satisfies(e -> assertCode(e, BookingErrors.ALREADY_BOOKED));
        SessionInventory full = open(1, 1);
        full.occupySeat();
        assertThatThrownBy(() -> policy.checkCanBook(full, true, false, before))
                .satisfies(e -> assertCode(e, BookingErrors.SESSION_FULL));
    }

    @Test
    void staleOrOutOfOrderSessionEventsDoNotReopenBooking() {
        SessionInventory session = open(5, 2);
        assertThat(session.applyCancelled(3, START)).isTrue();
        // Опоздавшее SessionPublished (меньшая или большая версия) не откроет отменённое занятие
        assertThat(session.applyPublished(new SessionInventory.PublishedSnapshot(session.sessionId(), "Йога",
                UUID.randomUUID(), "Ольга", START, START.plusSeconds(60), 5, 2))).isFalse();
        assertThat(session.isOpen()).isFalse();
        assertThat(session.applyCancelled(3, START)).isFalse();

        SessionInventory tombstone = SessionInventory.cancelledTombstone(UUID.randomUUID(), 2, START);
        assertThat(tombstone.applyPublished(new SessionInventory.PublishedSnapshot(tombstone.sessionId(), "Йога",
                UUID.randomUUID(), "Ольга", START, START.plusSeconds(60), 5, 1))).isFalse();
        assertThatThrownBy(() -> policy.checkCanBook(tombstone, true, false, START.minusSeconds(60)))
                .satisfies(e -> assertCode(e, BookingErrors.SESSION_CANCELLED));
    }

    @Test
    void finiteLimitIsEnforcedAndUnlimitedIsNot() {
        MembershipUsage finite = new MembershipUsage(UUID.randomUUID(), UUID.randomUUID(), 2, 0);
        finite.reserveVisit();
        finite.reserveVisit();
        assertThat(finite.remaining()).isZero();
        assertThatThrownBy(finite::reserveVisit).satisfies(e -> assertCode(e, BookingErrors.VISIT_LIMIT_REACHED));
        finite.returnVisit();
        assertThat(finite.remaining()).isEqualTo(1);

        MembershipUsage unlimited = new MembershipUsage(UUID.randomUUID(), UUID.randomUUID(), null, 0);
        for (int i = 0; i < 50; i++) {
            unlimited.reserveVisit();
        }
        assertThat(unlimited.remaining()).isNull();
    }

    @Test
    void bookingIsCancelledOnlyOnce() {
        Booking booking = Booking.confirm(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Мария",
                UUID.randomUUID(), START.minusSeconds(86400));
        assertThat(booking.version()).isEqualTo(1);
        assertThat(booking.cancel(Booking.CancelReason.USER, START)).isTrue();
        assertThat(booking.cancel(Booking.CancelReason.SESSION_CANCELLED, START)).isFalse();
        assertThat(booking.cancelReason()).isEqualTo(Booking.CancelReason.USER);
        assertThat(booking.version()).isEqualTo(2);
    }
}
