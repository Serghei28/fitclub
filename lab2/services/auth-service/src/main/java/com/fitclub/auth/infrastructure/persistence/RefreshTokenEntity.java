package com.fitclub.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** JPA-отображение refresh_tokens. */
@Entity
@Table(name = "refresh_tokens")
class RefreshTokenEntity {

    @Id
    UUID id;

    @Column(name = "user_id", nullable = false)
    UUID userId;

    @Column(name = "family_id", nullable = false)
    UUID familyId;

    // В схеме CHAR(64): SHA-256 в hex всегда ровно 64 символа
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64)
    String tokenHash;

    @Column(name = "issued_at", nullable = false)
    Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    Instant expiresAt;

    @Column(name = "revoked_at")
    Instant revokedAt;

    @Column(name = "replaced_by")
    UUID replacedBy;
}
