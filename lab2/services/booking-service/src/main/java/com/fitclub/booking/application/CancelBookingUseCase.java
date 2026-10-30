package com.fitclub.booking.application;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fitclub.booking.application.port.BookingPorts.BookingEventPublisher;
import com.fitclub.booking.application.port.BookingPorts.BookingRepository;
import com.fitclub.booking.application.port.BookingPorts.MembershipUsageRepository;
import com.fitclub.booking.application.port.BookingPorts.SessionInventoryRepository;
import com.fitclub.booking.application.port.BookingPorts.TransactionRunner;
import com.fitclub.booking.domain.Booking;
import com.fitclub.booking.domain.BookingErrors;
import com.fitclub.booking.domain.BookingPolicy;
import com.fitclub.booking.domain.MembershipUsage;
import com.fitclub.booking.domain.SessionInventory;

/**
 * Отмена своей записи пользователем — не позже чем за cancel-before до начала (равенство разрешено).
 * Место и посещение возвращаются одной транзакцией и только один раз: повторная отмена (или отмена,
 * совпавшая с отменой занятия администратором) ничего не возвращает повторно. Порядок блокировок —
 * как при записи: занятие → учёт посещений → бронь.
 */
@Service
public class CancelBookingUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelBookingUseCase.class);

    private final SessionInventoryRepository sessions;
    private final BookingRepository bookings;
    private final MembershipUsageRepository usages;
    private final BookingEventPublisher events;
    private final BookingPolicy policy;
    private final TransactionRunner tx;
    private final Clock clock;

    public CancelBookingUseCase(SessionInventoryRepository sessions, BookingRepository bookings,
            MembershipUsageRepository usages, BookingEventPublisher events, BookingPolicy policy, TransactionRunner tx,
            Clock clock) {
        this.sessions = sessions;
        this.bookings = bookings;
        this.usages = usages;
        this.events = events;
        this.policy = policy;
        this.tx = tx;
        this.clock = clock;
    }

    public BookingView cancelByUser(UUID userId, UUID bookingId) {
        return tx.inTransaction(() -> {
            Booking unlocked = bookings.find(bookingId).filter(b -> b.isOwnedBy(userId))
                    .orElseThrow(BookingErrors::bookingNotFound);
            SessionInventory session = sessions.lock(unlocked.sessionId()).orElseThrow(BookingErrors::sessionNotFound);
            MembershipUsage usage = usages.lock(unlocked.membershipId()).orElseThrow();
            Booking booking = bookings.lock(bookingId).orElseThrow(BookingErrors::bookingNotFound);
            if (!booking.isConfirmed()) {
                log.info("Booking already cancelled: booking_id={}", bookingId);
                return BookingView.of(booking, session);
            }
            policy.checkUserMayCancel(session.startsAt(), clock.instant());
            booking.cancel(Booking.CancelReason.USER, clock.instant());
            session.releaseSeat();
            usage.returnVisit();
            bookings.update(booking);
            sessions.update(session);
            usages.update(usage);
            events.bookingCancelled(booking, session);
            log.info("Booking cancelled by user: booking_id={}, seats_left={}", bookingId, session.available());
            return BookingView.of(booking, session);
        });
    }
}
