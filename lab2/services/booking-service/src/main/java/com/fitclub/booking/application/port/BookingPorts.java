package com.fitclub.booking.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.fitclub.booking.domain.Booking;
import com.fitclub.booking.domain.MembershipUsage;
import com.fitclub.booking.domain.SessionInventory;
import com.fitclub.common.web.PageResponse;

/**
 * Порты booking-service. Методы lock* берут блокировку строки до конца транзакции (SELECT … FOR UPDATE).
 * Порядок захвата во ВСЕХ сценариях одинаков: строка занятия → строка учёта посещений → бронь.
 * Так запись, пользовательская отмена и отмена занятия не блокируют друг друга крест-накрест (нет deadlock).
 */
public final class BookingPorts {

    private BookingPorts() {
    }

    public interface SessionInventoryRepository {

        Optional<SessionInventory> find(UUID sessionId);

        Optional<SessionInventory> lock(UUID sessionId);

        List<SessionInventory> findAll(Collection<UUID> sessionIds);

        /** @return false, если строка уже есть (гонка двух событий) */
        boolean insertIfAbsent(SessionInventory inventory);

        void update(SessionInventory inventory);
    }

    public interface BookingRepository {

        Optional<Booking> find(UUID bookingId);

        Optional<Booking> lock(UUID bookingId);

        boolean hasConfirmed(UUID sessionId, UUID userId);

        /** CONFIRMED-записи занятия; вызывать под блокировкой строки занятия. */
        List<Booking> findConfirmedBySession(UUID sessionId);

        PageResponse<Booking> findByUser(UUID userId, Booking.Status status, int page, int size);

        void add(Booking booking);

        void update(Booking booking);
    }

    public interface MembershipUsageRepository {

        /** Создаёт строку учёта, если её нет, и блокирует её. */
        MembershipUsage lockOrCreate(UUID membershipId, UUID userId, Integer visitLimit);

        Optional<MembershipUsage> lock(UUID membershipId);

        List<MembershipUsage> findByUser(UUID userId);

        void update(MembershipUsage usage);
    }

    /**
     * Узкий порт к membership-service: только «есть ли право на посещение в момент начала занятия».
     * Booking не зависит от операций с тарифами, заказами или оплатой (ISP).
     */
    public interface MembershipEligibilityReader {

        Optional<Eligibility> eligibility(UUID userId, Instant at);

        record Eligibility(UUID membershipId, Instant startsAt, Instant endsAt, Integer visitLimit) {
        }
    }

    /** События бронирования; реализация пишет их в outbox текущей транзакции. */
    public interface BookingEventPublisher {

        void bookingConfirmed(Booking booking, SessionInventory session);

        void bookingCancelled(Booking booking, SessionInventory session);
    }

    public interface TransactionRunner {

        <T> T inTransaction(Supplier<T> work);

        <T> T readOnly(Supplier<T> work);
    }
}
