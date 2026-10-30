package com.fitclub.booking.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fitclub.booking.application.port.BookingPorts.BookingRepository;
import com.fitclub.booking.application.port.BookingPorts.MembershipUsageRepository;
import com.fitclub.booking.application.port.BookingPorts.SessionInventoryRepository;
import com.fitclub.booking.application.port.BookingPorts.TransactionRunner;
import com.fitclub.booking.domain.Booking;
import com.fitclub.booking.domain.BookingErrors;
import com.fitclub.booking.domain.MembershipUsage;
import com.fitclub.booking.domain.SessionInventory;
import com.fitclub.common.error.ApiException;
import com.fitclub.common.security.AuthenticatedUser;
import com.fitclub.common.web.PageResponse;

/**
 * Чтение: свободные места (источник истины — Booking), свои записи и учёт посещений,
 * участники занятия — только назначенному тренеру и ADMIN (проверка здесь, а не только в UI).
 */
@Service
public class BookingQueryService {

    private final SessionInventoryRepository sessions;
    private final BookingRepository bookings;
    private final MembershipUsageRepository usages;
    private final TransactionRunner tx;

    public BookingQueryService(SessionInventoryRepository sessions, BookingRepository bookings,
            MembershipUsageRepository usages, TransactionRunner tx) {
        this.sessions = sessions;
        this.bookings = bookings;
        this.usages = usages;
        this.tx = tx;
    }

    public List<SessionInventory> availability(Collection<UUID> sessionIds) {
        return tx.readOnly(() -> sessions.findAll(sessionIds));
    }

    public PageResponse<BookingView> myBookings(UUID userId, Booking.Status status, int page, int size) {
        return tx.readOnly(() -> {
            PageResponse<Booking> result = bookings.findByUser(userId, status, page, Math.min(size, PageResponse.MAX_SIZE));
            Map<UUID, SessionInventory> snapshots = sessions.findAll(result.items().stream().map(Booking::sessionId)
                    .distinct().toList()).stream().collect(Collectors.toMap(SessionInventory::sessionId,
                    Function.identity()));
            return result.map(b -> BookingView.of(b, snapshots.get(b.sessionId())));
        });
    }

    public List<MembershipUsage> myUsage(UUID userId) {
        return tx.readOnly(() -> usages.findByUser(userId));
    }

    public record Participants(SessionInventory session, List<Booking> bookings) {
    }

    public Participants participants(UUID sessionId, AuthenticatedUser viewer) {
        return tx.readOnly(() -> {
            SessionInventory session = sessions.find(sessionId).orElseThrow(BookingErrors::sessionNotFound);
            boolean assignedTrainer = viewer.hasRole("TRAINER") && viewer.id().equals(session.trainerId());
            if (!assignedTrainer && !viewer.isAdmin()) {
                throw ApiException.forbidden("Список участников доступен только тренеру этого занятия и администратору.");
            }
            return new Participants(session, bookings.findConfirmedBySession(sessionId));
        });
    }
}
