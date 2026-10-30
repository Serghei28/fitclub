package com.fitclub.auth.application.port;

import java.time.Instant;

import com.fitclub.auth.domain.User;

/** Порт выпуска access-токена. Реализация — подписанный RS256 JWT (infrastructure.security). */
public interface AccessTokenIssuer {

    IssuedAccessToken issue(User user, Instant now);

    record IssuedAccessToken(String value, Instant expiresAt) {
    }
}
