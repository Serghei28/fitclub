package com.fitclub.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    /** SELECT ... FOR UPDATE: вторая параллельная ротация того же токена ждёт и затем видит его заменённым. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshTokenEntity t where t.tokenHash = :hash")
    Optional<RefreshTokenEntity> findByHashForUpdate(@Param("hash") String hash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshTokenEntity t set t.revokedAt = :now where t.familyId = :family and t.revokedAt is null")
    int revokeFamily(@Param("family") UUID familyId, @Param("now") Instant now);
}
