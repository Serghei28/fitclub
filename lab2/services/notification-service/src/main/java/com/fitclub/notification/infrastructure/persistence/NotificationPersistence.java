package com.fitclub.notification.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fitclub.common.web.PageResponse;
import com.fitclub.notification.application.port.NotificationPorts.BookingStateRepository;
import com.fitclub.notification.application.port.NotificationPorts.NotificationRepository;
import com.fitclub.notification.application.port.NotificationPorts.ReminderRepository;
import com.fitclub.notification.domain.BookingState;
import com.fitclub.notification.domain.Notification;
import com.fitclub.notification.domain.ReminderJob;

/** JPA-отображения notification_db и адаптеры портов хранения. */
final class NotificationPersistence {

    private NotificationPersistence() {
    }

    @Entity(name = "NotificationEntity")
    @Table(name = "notifications")
    static class NotificationEntity {
        @Id
        UUID id;
        @Column(name = "user_id", nullable = false)
        UUID userId;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 40)
        Notification.Type type;
        @Column(nullable = false, length = 150)
        String title;
        @Column(nullable = false, length = 1000)
        String body;
        @Column(name = "source_event_id")
        UUID sourceEventId;
        @Column(name = "dedup_key", nullable = false, length = 200)
        String dedupKey;
        @Column(name = "created_at", nullable = false)
        Instant createdAt;
        @Column(name = "read_at")
        Instant readAt;
    }

    @Entity(name = "BookingProjectionEntity")
    @Table(name = "booking_projection")
    static class BookingProjectionEntity {
        @Id
        @Column(name = "booking_id")
        UUID bookingId;
        @Column(name = "user_id", nullable = false)
        UUID userId;
        @Column(name = "session_id")
        UUID sessionId;
        @Column(name = "session_title", length = 150)
        String sessionTitle;
        @Column(name = "starts_at")
        Instant startsAt;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 16)
        BookingState.Status status;
        @Column(name = "booking_version", nullable = false)
        long bookingVersion;
        @Column(name = "updated_at", nullable = false)
        Instant updatedAt;
    }

    @Entity(name = "ReminderJobEntity")
    @Table(name = "reminder_jobs")
    static class ReminderJobEntity {
        @Id
        UUID id;
        @Column(name = "booking_id", nullable = false)
        UUID bookingId;
        @Column(name = "user_id", nullable = false)
        UUID userId;
        @Column(name = "session_title", nullable = false, length = 150)
        String sessionTitle;
        @Column(name = "starts_at", nullable = false)
        Instant startsAt;
        @Column(name = "remind_at", nullable = false)
        Instant remindAt;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 16)
        ReminderJob.Status status;
        @Column(nullable = false)
        int attempts;
        @Column(name = "created_at", nullable = false)
        Instant createdAt;
        @Column(name = "processed_at")
        Instant processedAt;
    }

    @Repository
    static class JpaNotificationRepository implements NotificationRepository {

        private final NotificationJpaRepository jpa;
        private final JdbcTemplate jdbc;

        JpaNotificationRepository(NotificationJpaRepository jpa, JdbcTemplate jdbc) {
            this.jpa = jpa;
            this.jdbc = jdbc;
        }

        /** Используется InApp-каналом: INSERT … ON CONFLICT (dedup_key) DO NOTHING. */
        boolean addIfAbsent(Notification n) {
            return jdbc.update("""
                    insert into notifications (id, user_id, type, title, body, source_event_id, dedup_key, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (dedup_key) do nothing
                    """, n.id(), n.userId(), n.type().name(), n.title(), n.body(), n.sourceEventId(), n.dedupKey(),
                    Timestamp.from(n.createdAt())) == 1;
        }

        @Override
        public Optional<Notification> findById(UUID id) {
            return jpa.findById(id).map(JpaNotificationRepository::toDomain);
        }

        @Override
        public PageResponse<Notification> findByUser(UUID userId, boolean unreadOnly, int page, int size) {
            PageRequest pageable = PageRequest.of(page, size);
            Page<NotificationEntity> result = unreadOnly
                    ? jpa.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, pageable)
                    : jpa.findByUserIdOrderByCreatedAtDesc(userId, pageable);
            return PageResponse.of(result.getContent().stream().map(JpaNotificationRepository::toDomain).toList(),
                    page, size, result.getTotalElements());
        }

        @Override
        public long countUnread(UUID userId) {
            return jpa.countByUserIdAndReadAtIsNull(userId);
        }

        @Override
        public void update(Notification n) {
            jpa.findById(n.id()).ifPresent(e -> e.readAt = n.readAt());
        }

        static Notification toDomain(NotificationEntity e) {
            return Notification.restore(e.id, e.userId, e.type, e.title, e.body, e.sourceEventId, e.dedupKey,
                    e.createdAt, e.readAt);
        }
    }

    @Repository
    static class JpaBookingStateRepository implements BookingStateRepository {

        private final BookingProjectionJpaRepository jpa;
        private final EntityManager em;
        private final java.time.Clock clock;

        JpaBookingStateRepository(BookingProjectionJpaRepository jpa, EntityManager em, java.time.Clock clock) {
            this.jpa = jpa;
            this.em = em;
            this.clock = clock;
        }

        @Override
        public Optional<BookingState> lock(UUID bookingId) {
            BookingProjectionEntity e = em.find(BookingProjectionEntity.class, bookingId);
            if (e == null) {
                return Optional.empty();
            }
            em.refresh(e, LockModeType.PESSIMISTIC_WRITE);
            return Optional.of(new BookingState(e.bookingId, e.userId, e.sessionId, e.sessionTitle, e.startsAt,
                    e.status, e.bookingVersion));
        }

        @Override
        public void save(BookingState s) {
            BookingProjectionEntity e = jpa.findById(s.bookingId()).orElseGet(BookingProjectionEntity::new);
            e.bookingId = s.bookingId();
            e.userId = s.userId();
            e.sessionId = s.sessionId();
            e.sessionTitle = s.sessionTitle();
            e.startsAt = s.startsAt();
            e.status = s.status();
            e.bookingVersion = s.version();
            e.updatedAt = clock.instant();
            jpa.saveAndFlush(e);
        }
    }

    @Repository
    static class JpaReminderRepository implements ReminderRepository {

        private final ReminderJobJpaRepository jpa;
        private final JdbcTemplate jdbc;
        private final EntityManager em;

        JpaReminderRepository(ReminderJobJpaRepository jpa, JdbcTemplate jdbc, EntityManager em) {
            this.jpa = jpa;
            this.jdbc = jdbc;
            this.em = em;
        }

        @Override
        public boolean addIfAbsent(ReminderJob j) {
            return jdbc.update("""
                    insert into reminder_jobs (id, booking_id, user_id, session_title, starts_at, remind_at, status,
                        attempts, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, 0, ?) on conflict (booking_id) do nothing
                    """, j.id(), j.bookingId(), j.userId(), j.sessionTitle(), Timestamp.from(j.startsAt()),
                    Timestamp.from(j.remindAt()), j.status().name(), Timestamp.from(j.createdAt())) == 1;
        }

        @Override
        public Optional<ReminderJob> lockByBooking(UUID bookingId) {
            Optional<ReminderJobEntity> found = jpa.findByBookingId(bookingId);
            found.ifPresent(e -> em.refresh(e, LockModeType.PESSIMISTIC_WRITE));
            return found.map(JpaReminderRepository::toDomain);
        }

        @Override
        public List<ReminderJob> claimDue(Instant now, int limit) {
            return jpa.claimDue(now, limit).stream().map(JpaReminderRepository::toDomain).toList();
        }

        @Override
        public void update(ReminderJob j) {
            ReminderJobEntity e = jpa.findById(j.id()).orElseThrow();
            e.status = j.status();
            e.attempts = j.attempts();
            e.processedAt = j.processedAt();
            jpa.flush();
        }

        static ReminderJob toDomain(ReminderJobEntity e) {
            return ReminderJob.restore(e.id, e.bookingId, e.userId, e.sessionTitle, e.startsAt, e.remindAt, e.status,
                    e.attempts, e.createdAt, e.processedAt);
        }
    }
}

interface NotificationJpaRepository extends JpaRepository<NotificationPersistence.NotificationEntity, UUID> {

    Page<NotificationPersistence.NotificationEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, org.springframework.data.domain.Pageable p);

    Page<NotificationPersistence.NotificationEntity> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(UUID userId,
            org.springframework.data.domain.Pageable p);

    long countByUserIdAndReadAtIsNull(UUID userId);
}

interface ReminderJobJpaRepository extends JpaRepository<NotificationPersistence.ReminderJobEntity, UUID> {

    Optional<NotificationPersistence.ReminderJobEntity> findByBookingId(UUID bookingId);

    @Query(value = """
            select * from reminder_jobs
            where status = 'PENDING' and remind_at <= :now
            order by remind_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<NotificationPersistence.ReminderJobEntity> claimDue(@Param("now") Instant now, @Param("limit") int limit);
}

interface BookingProjectionJpaRepository extends JpaRepository<NotificationPersistence.BookingProjectionEntity, UUID> {
}
