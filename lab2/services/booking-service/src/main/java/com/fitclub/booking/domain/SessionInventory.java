package com.fitclub.booking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Локальная проекция занятия из событий schedule-service и счётчик занятых мест.
 * capacity — неизменяемое условие от Schedule; bookedCount меняет только Booking.
 * Версия агрегата защищает от устаревших и пришедших не по порядку событий: применяется только событие
 * с версией больше уже применённой. Отмена, пришедшая раньше публикации, создаёт «надгробие» (CANCELLED),
 * и опоздавшее SessionPublished с меньшей версией его уже не откроет.
 */
public final class SessionInventory {

    public enum Status { OPEN, CANCELLED }

    private final UUID sessionId;
    private String title;
    private UUID trainerId;
    private String trainerDisplayName;
    private Instant startsAt;
    private Instant endsAt;
    private Integer capacity;
    private int bookedCount;
    private Status status;
    private long aggregateVersion;
    private Instant cancelledAt;

    private SessionInventory(UUID sessionId, String title, UUID trainerId, String trainerDisplayName, Instant startsAt,
            Instant endsAt, Integer capacity, int bookedCount, Status status, long aggregateVersion,
            Instant cancelledAt) {
        this.sessionId = Objects.requireNonNull(sessionId);
        this.title = title;
        this.trainerId = trainerId;
        this.trainerDisplayName = trainerDisplayName;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.capacity = capacity;
        this.bookedCount = bookedCount;
        this.status = status;
        this.aggregateVersion = aggregateVersion;
        this.cancelledAt = cancelledAt;
    }

    public static SessionInventory restore(UUID sessionId, String title, UUID trainerId, String trainerDisplayName,
            Instant startsAt, Instant endsAt, Integer capacity, int bookedCount, Status status, long aggregateVersion,
            Instant cancelledAt) {
        return new SessionInventory(sessionId, title, trainerId, trainerDisplayName, startsAt, endsAt, capacity,
                bookedCount, status, aggregateVersion, cancelledAt);
    }

    /** Снимок занятия из SessionPublished. */
    public record PublishedSnapshot(UUID sessionId, String title, UUID trainerId, String trainerDisplayName,
            Instant startsAt, Instant endsAt, int capacity, long version) {
    }

    public static SessionInventory fromPublished(PublishedSnapshot s) {
        return new SessionInventory(s.sessionId(), s.title(), s.trainerId(), s.trainerDisplayName(), s.startsAt(),
                s.endsAt(), s.capacity(), 0, Status.OPEN, s.version(), null);
    }

    /** «Надгробие»: отмена пришла раньше публикации. */
    public static SessionInventory cancelledTombstone(UUID sessionId, long version, Instant cancelledAt) {
        return new SessionInventory(sessionId, null, null, null, null, null, null, 0, Status.CANCELLED, version,
                cancelledAt);
    }

    /** @return true, если событие применено; false — устаревшее (версия не больше применённой). */
    public boolean applyPublished(PublishedSnapshot s) {
        if (s.version() <= aggregateVersion || status == Status.CANCELLED) {
            return false;
        }
        title = s.title();
        trainerId = s.trainerId();
        trainerDisplayName = s.trainerDisplayName();
        startsAt = s.startsAt();
        endsAt = s.endsAt();
        capacity = s.capacity();
        aggregateVersion = s.version();
        return true;
    }

    /** @return true, если отмена применена сейчас; false — устаревшая версия или уже отменено. */
    public boolean applyCancelled(long version, Instant at) {
        if (status == Status.CANCELLED || version < aggregateVersion) {
            return false;
        }
        status = Status.CANCELLED;
        aggregateVersion = version;
        cancelledAt = at;
        return true;
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    public boolean hasStartedAt(Instant now) {
        return !now.isBefore(startsAt);
    }

    public int available() {
        return capacity == null ? 0 : Math.max(0, capacity - bookedCount);
    }

    public boolean isFull() {
        return capacity == null || bookedCount >= capacity;
    }

    public void occupySeat() {
        if (isFull()) {
            throw BookingErrors.sessionFull();
        }
        bookedCount++;
    }

    public void releaseSeat() {
        if (bookedCount > 0) {
            bookedCount--;
        }
    }

    public UUID sessionId() {
        return sessionId;
    }

    public String title() {
        return title;
    }

    public UUID trainerId() {
        return trainerId;
    }

    public String trainerDisplayName() {
        return trainerDisplayName;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public Integer capacity() {
        return capacity;
    }

    public int bookedCount() {
        return bookedCount;
    }

    public Status status() {
        return status;
    }

    public long aggregateVersion() {
        return aggregateVersion;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }
}
