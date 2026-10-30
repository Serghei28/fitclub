package com.fitclub.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.booking.application.port.BookingPorts.BookingEventPublisher;
import com.fitclub.booking.application.port.BookingPorts.BookingRepository;
import com.fitclub.booking.application.port.BookingPorts.MembershipUsageRepository;
import com.fitclub.booking.application.port.BookingPorts.SessionInventoryRepository;
import com.fitclub.booking.domain.Booking;
import com.fitclub.booking.domain.MembershipUsage;
import com.fitclub.booking.domain.SessionInventory;

/**
 * Применение событий schedule-service к локальной проекции session_inventory.
 * Вызывается внутри транзакции обработчика событий (вместе с processed_events).
 *
 * <p>Порядок событий не гарантирован: применяется только событие с версией больше применённой.
 * SessionCancelled закрывает запись и отменяет все действующие бронирования с возвратом места и посещения
 * (независимо от пользовательского окна отмены), по одному событию BookingCancelled на каждую бронь.
 * Пока событие не доставлено, Booking продолжает считать занятие открытым — согласованность eventual.
 */
@Service
public class SessionProjectionHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionProjectionHandler.class);

    private final SessionInventoryRepository sessions;
    private final BookingRepository bookings;
    private final MembershipUsageRepository usages;
    private final BookingEventPublisher events;
    private final Clock clock;

    public SessionProjectionHandler(SessionInventoryRepository sessions, BookingRepository bookings,
            MembershipUsageRepository usages, BookingEventPublisher events, Clock clock) {
        this.sessions = sessions;
        this.bookings = bookings;
        this.usages = usages;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void onSessionPublished(SessionInventory.PublishedSnapshot snapshot) {
        Optional<SessionInventory> existing = sessions.lock(snapshot.sessionId());
        if (existing.isEmpty()) {
            if (sessions.insertIfAbsent(SessionInventory.fromPublished(snapshot))) {
                log.info("Session opened for booking: session_id={}, capacity={}", snapshot.sessionId(),
                        snapshot.capacity());
                return;
            }
            existing = sessions.lock(snapshot.sessionId());
        }
        SessionInventory inventory = existing.orElseThrow();
        if (inventory.applyPublished(snapshot)) {
            sessions.update(inventory);
            log.info("Session snapshot updated: session_id={}, version={}", snapshot.sessionId(), snapshot.version());
        } else {
            log.info("Stale or superseded SessionPublished ignored: session_id={}, event_version={}, current={}, "
                    + "status={}", snapshot.sessionId(), snapshot.version(), inventory.aggregateVersion(),
                    inventory.status());
        }
    }

    @Transactional
    public void onSessionCancelled(UUID sessionId, long version, Instant cancelledAt) {
        Optional<SessionInventory> existing = sessions.lock(sessionId);
        if (existing.isEmpty()) {
            if (sessions.insertIfAbsent(SessionInventory.cancelledTombstone(sessionId, version, cancelledAt))) {
                log.info("Session cancelled before its publication arrived (tombstone): session_id={}", sessionId);
                return;
            }
            existing = sessions.lock(sessionId);
        }
        SessionInventory session = existing.orElseThrow();
        if (!session.applyCancelled(version, cancelledAt)) {
            log.info("SessionCancelled already applied or stale: session_id={}", sessionId);
            return;
        }
        Instant now = clock.instant();
        // Строка занятия уже заблокирована; далее учёт посещений → бронь (тот же порядок, что при записи/отмене)
        List<Booking> confirmed = bookings.findConfirmedBySession(sessionId).stream()
                .sorted(Comparator.comparing(b -> b.membershipId().toString())).toList();
        for (Booking unlocked : confirmed) {
            MembershipUsage usage = usages.lock(unlocked.membershipId()).orElseThrow();
            Booking booking = bookings.lock(unlocked.id()).orElseThrow();
            if (booking.cancel(Booking.CancelReason.SESSION_CANCELLED, now)) {
                usage.returnVisit();
                session.releaseSeat();
                bookings.update(booking);
                usages.update(usage);
                events.bookingCancelled(booking, session);
            }
        }
        sessions.update(session);
        log.info("Session cancelled: session_id={}, bookings_cancelled={}", sessionId, confirmed.size());
    }
}
