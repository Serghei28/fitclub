package com.fitclub.auth.application;

import java.time.Instant;

import com.fitclub.auth.domain.User;

/** Результат входа или обновления сессии. refreshToken — исходное значение, в БД хранится только его хеш. */
public record TokenPair(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
        Instant refreshTokenExpiresAt, User user) {
}
