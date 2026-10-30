package com.fitclub.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fitclub.booking.application.port.BookingPorts.BookingEventPublisher;
import com.fitclub.booking.application.port.BookingPorts.BookingRepository;
import com.fitclub.booking.application.port.BookingPorts.MembershipEligibilityReader;
import com.fitclub.booking.application.port.BookingPorts.MembershipEligibilityReader.Eligibility;
import com.fitclub.booking.application.port.BookingPorts.MembershipUsageRepository;
import com.fitclub.booking.application.port.BookingPorts.SessionInventoryRepository;
import com.fitclub.booking.application.port.BookingPorts.TransactionRunner;
import com.fitclub.booking.domain.Booking;
import com.fitclub.booking.domain.BookingErrors;
import com.fitclub.booking.domain.BookingPolicy;
import com.fitclub.booking.domain.MembershipUsage;
import com.fitclub.booking.domain.SessionInventory;
import com.fitclub.common.idempotency.IdempotencyStore;
import com.fitclub.common.idempotency.IdempotencyStore.Fresh;
import com.fitclub.common.idempotency.IdempotencyStore.Outcome;

/**
 * Запись на занятие.
 * <ol>
 *   <li>повтор Idempotency-Key → сохранённый ответ сразу (даже если занятие уже заполнено или запись отменена);</li>
 *   <li>снимок занятия есть? иначе SESSION_NOT_READY (SessionPublished ещё не применён);</li>
 *   <li>право на посещение в момент начала занятия — HTTP в Membership ВНЕ транзакции, с таймаутом;
 *       недоступность Membership → ошибка зависимости, а не «разрешено»;</li>
 *   <li>одна локальная транзакция: блокировка строки занятия → блокировка учёта посещений абонемента →
 *       проверки BookingPolicy → место + посещение + запись + BookingConfirmed в outbox.</li>
 * </ol>
 * Блокировка строки занятия делает проверку «есть место» и её изменение атомарными: две параллельные
 * записи на последнее место выполняются по очереди, вторая видит, что мест нет. Блокировка учёта посещений
 * делает то же для последнего посещения абонемента при записи на разные занятия.
 * Ограничения БД (booked_count &lt;= capacity, used_count &lt;= visit_limit, одна CONFIRMED-запись на занятие)
 * — последняя линия защиты.
 */
@Service
public class CreateBookingUseCase {

    public static final String OPERATION = "CREATE_BOOKING";

    private static final Logger log = LoggerFactory.getLogger(CreateBookingUseCase.class);

    private final SessionInventoryRepository sessions;
    private final BookingRepository bookings;
    private final MembershipUsageRepository usages;
    private final MembershipEligibilityReader eligibilityReader;
    private final BookingEventPublisher events;
    private final BookingPolicy policy;
    private final IdempotencyStore idempotency;
    private final TransactionRunner tx;
    private final Clock clock;

    public CreateBookingUseCase(SessionInventoryRepository sessions, BookingRepository bookings,
            MembershipUsageRepository usages, MembershipEligibilityReader eligibilityReader,
            BookingEventPublisher events, BookingPolicy policy, IdempotencyStore idempotency, TransactionRunner tx,
            Clock clock) {
        this.sessions = sessions;
        this.bookings = bookings;
        this.usages = usages;
        this.eligibilityReader = eligibilityReader;
        this.events = events;
        this.policy = policy;
        this.idempotency = idempotency;
        this.tx = tx;
        this.clock = clock;
    }

    public record Request(UUID sessionId) {
    }

    public record Booker(UUID userId, String displayName) {
    }

    public Outcome<BookingView> book(Booker booker, UUID idempotencyKey, Request request) {
        Optional<Outcome<BookingView>> replay = tx.readOnly(() -> idempotency.lookup(booker.userId(), OPERATION,
                idempotencyKey, request, BookingView.class));
        if (replay.isPresent()) {
            return replay.get();
        }
        SessionInventory snapshot = tx.readOnly(() -> sessions.find(request.sessionId()))
                .orElseThrow(BookingErrors::sessionNotReady);
        Instant now = clock.instant();
        // Быстрый отказ без сетевого вызова; окончательная проверка — под блокировкой в транзакции
        if (!snapshot.isOpen()) {
            throw BookingErrors.sessionCancelled();
        }
        if (snapshot.hasStartedAt(now)) {
            throw BookingErrors.alreadyStarted();
        }
        Optional<Eligibility> eligibility = eligibilityReader.eligibility(booker.userId(), snapshot.startsAt());

        return tx.inTransaction(() -> idempotency.execute(booker.userId(), OPERATION, idempotencyKey, request,
                BookingView.class, () -> bookOnce(booker, request.sessionId(), eligibility)));
    }

    Fresh<BookingView> bookOnce(Booker booker, UUID sessionId, Optional<Eligibility> eligibility) {
        Instant now = clock.instant();
        SessionInventory session = sessions.lock(sessionId).orElseThrow(BookingErrors::sessionNotReady);
        policy.checkCanBook(session, eligibility.isPresent(), bookings.hasConfirmed(sessionId, booker.userId()), now);

        Eligibility membership = eligibility.orElseThrow();
        MembershipUsage usage = usages.lockOrCreate(membership.membershipId(), booker.userId(),
                membership.visitLimit());
        usage.reserveVisit();
        session.occupySeat();

        Booking booking = Booking.confirm(UUID.randomUUID(), sessionId, booker.userId(), booker.displayName(),
                membership.membershipId(), now);
        bookings.add(booking);
        sessions.update(session);
        usages.update(usage);
        events.bookingConfirmed(booking, session);
        log.info("Booking confirmed: booking_id={}, session_id={}, seats_left={}, visits_left={}", booking.id(),
                sessionId, session.available(), usage.unlimited() ? "unlimited" : usage.remaining());
        return new Fresh<>(BookingView.of(booking, session), HttpStatus.CREATED, booking.id());
    }
}
