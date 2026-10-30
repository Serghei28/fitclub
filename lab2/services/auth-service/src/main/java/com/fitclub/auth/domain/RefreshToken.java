package com.fitclub.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Сохранённый refresh-токен (только SHA-256 хеш, сам токен знает клиент).
 * Токены одной цепочки ротаций имеют общий familyId. Если уже заменённый токен
 * предъявлен повторно, это признак кражи: отзывается вся цепочка.
 */
public final class RefreshToken {

    private final UUID id;
    private final UUID userId;
    private final UUID familyId;
    private final String tokenHash;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private Instant revokedAt;
    private UUID replacedBy;

    private RefreshToken(UUID id, UUID userId, UUID familyId, String tokenHash, Instant issuedAt, Instant expiresAt,
            Instant revokedAt, UUID replacedBy) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.familyId = Objects.requireNonNull(familyId);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.replacedBy = replacedBy;
    }

    public static RefreshToken issue(UUID id, UUID userId, UUID familyId, String tokenHash, Instant now,
            Duration ttl) {
        return new RefreshToken(id, userId, familyId, tokenHash, now, now.plus(ttl), null, null);
    }

    public static RefreshToken restore(UUID id, UUID userId, UUID familyId, String tokenHash, Instant issuedAt,
            Instant expiresAt, Instant revokedAt, UUID replacedBy) {
        return new RefreshToken(id, userId, familyId, tokenHash, issuedAt, expiresAt, revokedAt, replacedBy);
    }

    /** Срок действия — полуинтервал [issuedAt, expiresAt). */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Токен уже был заменён новым: его повторное предъявление — повторное использование. */
    public boolean wasRotated() {
        return replacedBy != null;
    }

    public void rotateTo(UUID nextTokenId, Instant now) {
        this.revokedAt = now;
        this.replacedBy = nextTokenId;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID familyId() {
        return familyId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public UUID replacedBy() {
        return replacedBy;
    }
}
