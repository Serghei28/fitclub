package com.fitclub.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Сохраняемая задача напоминания о занятии: переживает перезапуск сервиса.
 * PENDING → SENT (отправлено один раз) | CANCELLED (бронь отменена) | SKIPPED (занятие уже началось).
 */
public final class ReminderJob {

    public enum Status { PENDING, SENT, CANCELLED, SKIPPED }

    private final UUID id;
    private final UUID bookingId;
    private final UUID userId;
    private final String sessionTitle;
    private final Instant startsAt;
    private final Instant remindAt;
    private Status status;
    private int attempts;
    private final Instant createdAt;
    private Instant processedAt;

    private ReminderJob(UUID id, UUID bookingId, UUID userId, String sessionTitle, Instant startsAt, Instant remindAt,
            Status status, int attempts, Instant createdAt, Instant processedAt) {
        this.id = Objects.requireNonNull(id);
        this.bookingId = Objects.requireNonNull(bookingId);
        this.userId = Objects.requireNonNull(userId);
        this.sessionTitle = sessionTitle;
        this.startsAt = Objects.requireNonNull(startsAt);
        this.remindAt = Objects.requireNonNull(remindAt);
        this.status = status;
        this.attempts = attempts;
        this.createdAt = createdAt;
        this.processedAt = processedAt;
    }

    /**
     * Правило планирования (REMINDER_LEAD_MINUTES):
     * <ul>
     *   <li>занятие уже началось → напоминание не создаётся;</li>
     *   <li>момент «за lead до начала» ещё впереди → напомнить в этот момент;</li>
     *   <li>момент уже прошёл, но занятие впереди → напомнить при ближайшей обработке (remindAt = now).</li>
     * </ul>
     */
    public static Optional<ReminderJob> plan(UUID bookingId, UUID userId, String sessionTitle, Instant startsAt,
            Duration lead, Instant now) {
        if (!startsAt.isAfter(now)) {
            return Optional.empty();
        }
        Instant ideal = startsAt.minus(lead);
        Instant remindAt = ideal.isAfter(now) ? ideal : now;
        return Optional.of(new ReminderJob(UUID.randomUUID(), bookingId, userId, sessionTitle, startsAt, remindAt,
                Status.PENDING, 0, now, null));
    }

    public static ReminderJob restore(UUID id, UUID bookingId, UUID userId, String sessionTitle, Instant startsAt,
            Instant remindAt, Status status, int attempts, Instant createdAt, Instant processedAt) {
        return new ReminderJob(id, bookingId, userId, sessionTitle, startsAt, remindAt, status, attempts, createdAt,
                processedAt);
    }

    /** Что сделать с наступившей задачей сейчас. */
    public enum Decision { SEND, SKIP_STARTED }

    public Decision decide(Instant now) {
        return startsAt.isAfter(now) ? Decision.SEND : Decision.SKIP_STARTED;
    }

    public void markSent(Instant now) {
        status = Status.SENT;
        attempts++;
        processedAt = now;
    }

    public void markSkipped(Instant now) {
        status = Status.SKIPPED;
        attempts++;
        processedAt = now;
    }

    /** @return true, если задача была ожидающей и теперь отменена. */
    public boolean cancel(Instant now) {
        if (status != Status.PENDING) {
            return false;
        }
        status = Status.CANCELLED;
        processedAt = now;
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID bookingId() {
        return bookingId;
    }

    public UUID userId() {
        return userId;
    }

    public String sessionTitle() {
        return sessionTitle;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant remindAt() {
        return remindAt;
    }

    public Status status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant processedAt() {
        return processedAt;
    }
}
