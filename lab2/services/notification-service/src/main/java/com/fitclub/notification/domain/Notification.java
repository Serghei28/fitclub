package com.fitclub.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Внутреннее (InApp) уведомление пользователя. dedupKey описывает факт, о котором уведомляем
 * (например, BOOKING_CONFIRMED:&lt;booking_id&gt;:1): повтор события, даже с новым event_id, не создаёт дубль.
 */
public final class Notification {

    public enum Type { MEMBERSHIP_ACTIVATED, BOOKING_CONFIRMED, BOOKING_CANCELLED, SESSION_CANCELLED, SESSION_REMINDER }

    private final UUID id;
    private final UUID userId;
    private final Type type;
    private final String title;
    private final String body;
    private final UUID sourceEventId;
    private final String dedupKey;
    private final Instant createdAt;
    private Instant readAt;

    private Notification(UUID id, UUID userId, Type type, String title, String body, UUID sourceEventId,
            String dedupKey, Instant createdAt, Instant readAt) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.type = Objects.requireNonNull(type);
        this.title = title;
        this.body = body;
        this.sourceEventId = sourceEventId;
        this.dedupKey = Objects.requireNonNull(dedupKey);
        this.createdAt = createdAt;
        this.readAt = readAt;
    }

    public static Notification create(UUID userId, Type type, String title, String body, UUID sourceEventId,
            String dedupKey, Instant now) {
        return new Notification(UUID.randomUUID(), userId, type, title, body, sourceEventId, dedupKey, now, null);
    }

    public static Notification restore(UUID id, UUID userId, Type type, String title, String body, UUID sourceEventId,
            String dedupKey, Instant createdAt, Instant readAt) {
        return new Notification(id, userId, type, title, body, sourceEventId, dedupKey, createdAt, readAt);
    }

    /** Повторная отметка не меняет момент первого прочтения. */
    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    public boolean isOwnedBy(UUID candidate) {
        return userId.equals(candidate);
    }

    public boolean isRead() {
        return readAt != null;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public Type type() {
        return type;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public UUID sourceEventId() {
        return sourceEventId;
    }

    public String dedupKey() {
        return dedupKey;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant readAt() {
        return readAt;
    }
}
