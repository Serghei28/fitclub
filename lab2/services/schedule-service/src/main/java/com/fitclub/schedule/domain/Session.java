package com.fitclub.schedule.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Групповое занятие. Правила:
 * <ul>
 *   <li>начало раньше конца, длительность не больше 24 часов, вместимость 1..500;</li>
 *   <li>редактировать можно только DRAFT; после публикации время, тренер и вместимость зафиксированы —
 *       это снимает необходимость синхронизировать изменения с уже созданными бронированиями;</li>
 *   <li>опубликовать можно только будущее занятие с подтверждённым тренером;</li>
 *   <li>отменить можно черновик или опубликованное, но ещё не начавшееся занятие; повторная отмена ничего не меняет.</li>
 * </ul>
 * version увеличивается при каждом изменении и становится aggregate_version событий.
 * Время — Instant (UTC). Класс не зависит от Spring и JPA.
 */
public final class Session {

    public static final int MAX_CAPACITY = 500;
    public static final int MAX_TITLE_LENGTH = 150;
    public static final Duration MAX_DURATION = Duration.ofHours(24);

    private final UUID id;
    private UUID trainingTypeId;
    private UUID trainerId;
    private String trainerDisplayName;
    private String title;
    private Instant startsAt;
    private Instant endsAt;
    private int capacity;
    private SessionStatus status;
    private Instant publishedAt;
    private Instant cancelledAt;
    private String cancelReason;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Session(UUID id, UUID trainingTypeId, UUID trainerId, String trainerDisplayName, String title,
            Instant startsAt, Instant endsAt, int capacity, SessionStatus status, Instant publishedAt,
            Instant cancelledAt, String cancelReason, Instant createdAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.trainingTypeId = trainingTypeId;
        this.trainerId = trainerId;
        this.trainerDisplayName = trainerDisplayName;
        this.title = title;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.capacity = capacity;
        this.status = status;
        this.publishedAt = publishedAt;
        this.cancelledAt = cancelledAt;
        this.cancelReason = cancelReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Session createDraft(UUID id, UUID trainingTypeId, TrainerSnapshot trainer, String title,
            Instant startsAt, Instant endsAt, int capacity, Instant now) {
        String validTitle = validTitle(title);
        validateSchedule(startsAt, endsAt, capacity);
        return new Session(id, Objects.requireNonNull(trainingTypeId), trainer.trainerId(), trainer.displayName(),
                validTitle, startsAt, endsAt, capacity, SessionStatus.DRAFT, null, null, null, now, now, 0);
    }

    public static Session restore(UUID id, UUID trainingTypeId, UUID trainerId, String trainerDisplayName,
            String title, Instant startsAt, Instant endsAt, int capacity, SessionStatus status, Instant publishedAt,
            Instant cancelledAt, String cancelReason, Instant createdAt, Instant updatedAt, long version) {
        return new Session(id, trainingTypeId, trainerId, trainerDisplayName, title, startsAt, endsAt, capacity,
                status, publishedAt, cancelledAt, cancelReason, createdAt, updatedAt, version);
    }

    /** Изменения черновика: null в поле означает «не менять». */
    public record DraftChanges(UUID trainingTypeId, String title, Instant startsAt, Instant endsAt,
            Integer capacity) {
    }

    public void editDraft(DraftChanges changes, TrainerSnapshot newTrainer, Instant now) {
        if (status != SessionStatus.DRAFT) {
            throw ScheduleErrors.notEditable();
        }
        String newTitle = changes.title() == null ? title : validTitle(changes.title());
        Instant newStart = changes.startsAt() == null ? startsAt : changes.startsAt();
        Instant newEnd = changes.endsAt() == null ? endsAt : changes.endsAt();
        int newCapacity = changes.capacity() == null ? capacity : changes.capacity();
        validateSchedule(newStart, newEnd, newCapacity);

        if (changes.trainingTypeId() != null) {
            trainingTypeId = changes.trainingTypeId();
        }
        if (newTrainer != null) {
            trainerId = newTrainer.trainerId();
            trainerDisplayName = newTrainer.displayName();
        }
        title = newTitle;
        startsAt = newStart;
        endsAt = newEnd;
        capacity = newCapacity;
        touch(now);
    }

    /**
     * Публикация. verifiedTrainer — результат проверки в Auth прямо перед публикацией: имя обновляется
     * до актуального снимка, который уйдёт в событие SessionPublished.
     */
    public void publish(TrainerSnapshot verifiedTrainer, Instant now) {
        if (status != SessionStatus.DRAFT) {
            throw ScheduleErrors.invalidTransition(status, "опубликовать");
        }
        if (!verifiedTrainer.trainerId().equals(trainerId)) {
            throw new IllegalArgumentException("Verified trainer does not match the assigned trainer");
        }
        if (!startsAt.isAfter(now)) {
            throw ScheduleErrors.inPast();
        }
        validateSchedule(startsAt, endsAt, capacity);
        trainerDisplayName = verifiedTrainer.displayName();
        status = SessionStatus.PUBLISHED;
        publishedAt = now;
        touch(now);
    }

    public enum CancelOutcome {
        /** Уже было отменено: ничего не изменилось, событие не нужно. */
        ALREADY_CANCELLED,
        /** Отменён черновик: Booking о нём не знал, событие не нужно. */
        DRAFT_DISCARDED,
        /** Отменено опубликованное занятие: нужно событие SessionCancelled. */
        PUBLISHED_CANCELLED
    }

    public CancelOutcome cancel(String reason, Instant now) {
        if (status == SessionStatus.CANCELLED) {
            return CancelOutcome.ALREADY_CANCELLED;
        }
        if (status == SessionStatus.PUBLISHED && !startsAt.isAfter(now)) {
            throw ScheduleErrors.alreadyStarted();
        }
        CancelOutcome outcome = status == SessionStatus.PUBLISHED
                ? CancelOutcome.PUBLISHED_CANCELLED
                : CancelOutcome.DRAFT_DISCARDED;
        status = SessionStatus.CANCELLED;
        cancelledAt = now;
        cancelReason = reason == null || reason.isBlank() ? null : reason.strip();
        touch(now);
        return outcome;
    }

    public boolean isVisibleToMembers() {
        return status != SessionStatus.DRAFT;
    }

    private void touch(Instant now) {
        updatedAt = now;
        version++;
    }

    private static String validTitle(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > MAX_TITLE_LENGTH) {
            throw ScheduleErrors.invalidTitle();
        }
        return value;
    }

    private static void validateSchedule(Instant startsAt, Instant endsAt, int capacity) {
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)
                || Duration.between(startsAt, endsAt).compareTo(MAX_DURATION) > 0) {
            throw ScheduleErrors.invalidPeriod();
        }
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw ScheduleErrors.invalidCapacity();
        }
    }

    public UUID id() {
        return id;
    }

    public UUID trainingTypeId() {
        return trainingTypeId;
    }

    public UUID trainerId() {
        return trainerId;
    }

    public String trainerDisplayName() {
        return trainerDisplayName;
    }

    public String title() {
        return title;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public int capacity() {
        return capacity;
    }

    public SessionStatus status() {
        return status;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public String cancelReason() {
        return cancelReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
