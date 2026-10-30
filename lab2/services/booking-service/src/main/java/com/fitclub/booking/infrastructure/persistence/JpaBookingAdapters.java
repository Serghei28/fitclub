package com.fitclub.booking.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fitclub.booking.application.port.BookingPorts.BookingRepository;
import com.fitclub.booking.application.port.BookingPorts.MembershipUsageRepository;
import com.fitclub.booking.application.port.BookingPorts.SessionInventoryRepository;
import com.fitclub.booking.domain.Booking;
import com.fitclub.booking.domain.BookingErrors;
import com.fitclub.booking.domain.MembershipUsage;
import com.fitclub.booking.domain.SessionInventory;
import com.fitclub.booking.infrastructure.persistence.BookingEntities.BookingEntity;
import com.fitclub.booking.infrastructure.persistence.BookingEntities.InventoryEntity;
import com.fitclub.booking.infrastructure.persistence.BookingEntities.UsageEntity;
import com.fitclub.common.web.PageResponse;

/**
 * Адаптеры хранения booking-service. lock* — SELECT … FOR UPDATE (PESSIMISTIC_WRITE).
 * Вставки, возможные в гонке (проекция занятия, строка учёта посещений), выполняются через
 * INSERT … ON CONFLICT DO NOTHING, чтобы проигравший не получал ошибку, ломающую транзакцию.
 *
 * <p>Важно: блокирующее чтение всегда ПЕРЕЧИТЫВАЕТ строку (refresh с PESSIMISTIC_WRITE). Если сущность уже была
 * загружена в этой транзакции без блокировки, Hibernate на SELECT … FOR UPDATE вернул бы закэшированное
 * состояние, и сценарий принял бы решение по устаревшим данным (обнаружено конкурентным тестом отмены).
 */
final class JpaBookingAdapters {

    private JpaBookingAdapters() {
    }

    /** SELECT … FOR UPDATE с гарантированно свежим состоянием сущности. */
    static <E> Optional<E> lockFresh(EntityManager em, Class<E> type, UUID id) {
        E entity = em.find(type, id);
        if (entity == null) {
            return Optional.empty();
        }
        em.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(entity);
    }

    @Repository
    static class JpaSessionInventoryRepository implements SessionInventoryRepository {

        private final InventoryJpaRepository jpa;
        private final JdbcTemplate jdbc;
        private final EntityManager em;
        private final Clock clock;

        JpaSessionInventoryRepository(InventoryJpaRepository jpa, JdbcTemplate jdbc, EntityManager em, Clock clock) {
            this.jpa = jpa;
            this.jdbc = jdbc;
            this.em = em;
            this.clock = clock;
        }

        @Override
        public Optional<SessionInventory> find(UUID sessionId) {
            return jpa.findById(sessionId).map(JpaSessionInventoryRepository::toDomain);
        }

        @Override
        public Optional<SessionInventory> lock(UUID sessionId) {
            return lockFresh(em, InventoryEntity.class, sessionId).map(JpaSessionInventoryRepository::toDomain);
        }

        @Override
        public List<SessionInventory> findAll(Collection<UUID> sessionIds) {
            if (sessionIds.isEmpty()) {
                return List.of();
            }
            return jpa.findBySessionIdIn(sessionIds).stream().map(JpaSessionInventoryRepository::toDomain).toList();
        }

        @Override
        public boolean insertIfAbsent(SessionInventory s) {
            return jdbc.update("""
                    insert into session_inventory (session_id, title, trainer_id, trainer_display_name, starts_at,
                        ends_at, capacity, booked_count, status, aggregate_version, cancelled_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                    on conflict (session_id) do nothing
                    """, s.sessionId(), s.title(), s.trainerId(), s.trainerDisplayName(), ts(s.startsAt()),
                    ts(s.endsAt()), s.capacity(), s.status().name(), s.aggregateVersion(), ts(s.cancelledAt()),
                    Timestamp.from(clock.instant())) == 1;
        }

        @Override
        public void update(SessionInventory s) {
            InventoryEntity e = jpa.findById(s.sessionId()).orElseThrow(BookingErrors::sessionNotFound);
            e.title = s.title();
            e.trainerId = s.trainerId();
            e.trainerDisplayName = s.trainerDisplayName();
            e.startsAt = s.startsAt();
            e.endsAt = s.endsAt();
            e.capacity = s.capacity();
            e.bookedCount = s.bookedCount();
            e.status = s.status();
            e.aggregateVersion = s.aggregateVersion();
            e.cancelledAt = s.cancelledAt();
            e.updatedAt = clock.instant();
            jpa.flush();
        }

        private static Timestamp ts(java.time.Instant instant) {
            return instant == null ? null : Timestamp.from(instant);
        }

        static SessionInventory toDomain(InventoryEntity e) {
            return SessionInventory.restore(e.sessionId, e.title, e.trainerId, e.trainerDisplayName, e.startsAt,
                    e.endsAt, e.capacity, e.bookedCount, e.status, e.aggregateVersion, e.cancelledAt);
        }
    }

    @Repository
    static class JpaBookingRepository implements BookingRepository {

        private final BookingJpaRepository jpa;
        private final EntityManager em;

        JpaBookingRepository(BookingJpaRepository jpa, EntityManager em) {
            this.jpa = jpa;
            this.em = em;
        }

        @Override
        public Optional<Booking> find(UUID bookingId) {
            return jpa.findById(bookingId).map(JpaBookingRepository::toDomain);
        }

        @Override
        public Optional<Booking> lock(UUID bookingId) {
            return lockFresh(em, BookingEntity.class, bookingId).map(JpaBookingRepository::toDomain);
        }

        @Override
        public boolean hasConfirmed(UUID sessionId, UUID userId) {
            return jpa.existsBySessionIdAndUserIdAndStatus(sessionId, userId, Booking.Status.CONFIRMED);
        }

        @Override
        public List<Booking> findConfirmedBySession(UUID sessionId) {
            return jpa.findBySessionIdAndStatusOrderByCreatedAtAsc(sessionId, Booking.Status.CONFIRMED).stream()
                    .map(JpaBookingRepository::toDomain).toList();
        }

        @Override
        public PageResponse<Booking> findByUser(UUID userId, Booking.Status status, int page, int size) {
            PageRequest pageable = PageRequest.of(page, size);
            Page<BookingEntity> result = status == null
                    ? jpa.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                    : jpa.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable);
            return PageResponse.of(result.getContent().stream().map(JpaBookingRepository::toDomain).toList(), page,
                    size, result.getTotalElements());
        }

        @Override
        public void add(Booking b) {
            BookingEntity e = new BookingEntity();
            e.id = b.id();
            e.sessionId = b.sessionId();
            e.userId = b.userId();
            e.userDisplayName = b.userDisplayName();
            e.membershipId = b.membershipId();
            e.createdAt = b.createdAt();
            copy(b, e);
            jpa.saveAndFlush(e);
        }

        @Override
        public void update(Booking b) {
            BookingEntity e = jpa.findById(b.id()).orElseThrow(BookingErrors::bookingNotFound);
            copy(b, e);
            jpa.flush();
        }

        private static void copy(Booking b, BookingEntity e) {
            e.status = b.status();
            e.cancelReason = b.cancelReason();
            e.cancelledAt = b.cancelledAt();
            e.version = b.version();
        }

        static Booking toDomain(BookingEntity e) {
            return Booking.restore(e.id, e.sessionId, e.userId, e.userDisplayName, e.membershipId, e.status,
                    e.cancelReason, e.createdAt, e.cancelledAt, e.version);
        }
    }

    @Repository
    static class JpaMembershipUsageRepository implements MembershipUsageRepository {

        private final UsageJpaRepository jpa;
        private final JdbcTemplate jdbc;
        private final EntityManager em;
        private final Clock clock;

        JpaMembershipUsageRepository(UsageJpaRepository jpa, JdbcTemplate jdbc, EntityManager em, Clock clock) {
            this.jpa = jpa;
            this.jdbc = jdbc;
            this.em = em;
            this.clock = clock;
        }

        @Override
        public MembershipUsage lockOrCreate(UUID membershipId, UUID userId, Integer visitLimit) {
            jdbc.update("""
                    insert into membership_usage (membership_id, user_id, visit_limit, used_count, updated_at)
                    values (?, ?, ?, 0, ?) on conflict (membership_id) do nothing
                    """, membershipId, userId, visitLimit, Timestamp.from(clock.instant()));
            return lock(membershipId).orElseThrow();
        }

        @Override
        public Optional<MembershipUsage> lock(UUID membershipId) {
            return lockFresh(em, UsageEntity.class, membershipId).map(JpaMembershipUsageRepository::toDomain);
        }

        @Override
        public List<MembershipUsage> findByUser(UUID userId) {
            return jpa.findByUserId(userId).stream().map(JpaMembershipUsageRepository::toDomain).toList();
        }

        @Override
        public void update(MembershipUsage usage) {
            UsageEntity e = jpa.findById(usage.membershipId()).orElseThrow();
            e.usedCount = usage.usedCount();
            e.updatedAt = clock.instant();
            jpa.flush();
        }

        static MembershipUsage toDomain(UsageEntity e) {
            return new MembershipUsage(e.membershipId, e.userId, e.visitLimit, e.usedCount);
        }
    }
}
