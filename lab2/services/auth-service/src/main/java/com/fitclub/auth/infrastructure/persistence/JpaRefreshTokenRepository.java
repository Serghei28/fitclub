package com.fitclub.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.fitclub.auth.application.port.RefreshTokenRepository;
import com.fitclub.auth.domain.RefreshToken;

/** Адаптер порта RefreshTokenRepository. */
@Repository
class JpaRefreshTokenRepository implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpa;

    JpaRefreshTokenRepository(RefreshTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<RefreshToken> findByHashForUpdate(String tokenHash) {
        return jpa.findByHashForUpdate(tokenHash).map(JpaRefreshTokenRepository::toDomain);
    }

    @Override
    public void add(RefreshToken token) {
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.id = token.id();
        entity.userId = token.userId();
        entity.familyId = token.familyId();
        entity.tokenHash = token.tokenHash();
        entity.issuedAt = token.issuedAt();
        entity.expiresAt = token.expiresAt();
        copyMutable(token, entity);
        jpa.save(entity);
    }

    @Override
    public void update(RefreshToken token) {
        jpa.findById(token.id()).ifPresent(entity -> copyMutable(token, entity));
    }

    @Override
    public int revokeFamily(UUID familyId, Instant now) {
        return jpa.revokeFamily(familyId, now);
    }

    private static void copyMutable(RefreshToken token, RefreshTokenEntity entity) {
        entity.revokedAt = token.revokedAt();
        entity.replacedBy = token.replacedBy();
    }

    private static RefreshToken toDomain(RefreshTokenEntity e) {
        return RefreshToken.restore(e.id, e.userId, e.familyId, e.tokenHash, e.issuedAt, e.expiresAt, e.revokedAt,
                e.replacedBy);
    }
}
