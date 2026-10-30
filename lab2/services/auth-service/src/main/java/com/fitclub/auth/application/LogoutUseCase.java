package com.fitclub.auth.application;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.auth.application.port.RefreshTokenRepository;

/**
 * Выход: отзывает цепочку refresh-токенов текущей сессии. Операция идемпотентна:
 * неизвестный или уже отозванный токен не считается ошибкой.
 * Уже выданный access JWT остаётся действительным до своего exp (не более access-token-ttl):
 * сервисы проверяют его локально по подписи, без обращения к Auth.
 */
@Service
public class LogoutUseCase {

    private static final Logger log = LoggerFactory.getLogger(LogoutUseCase.class);

    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    public LogoutUseCase(RefreshTokenRepository refreshTokens, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokens.findByHashForUpdate(RefreshTokenSecrets.hash(rawRefreshToken)).ifPresent(token -> {
            refreshTokens.revokeFamily(token.familyId(), clock.instant());
            log.info("Logout: refresh tokens revoked, user_id={}", token.userId());
        });
    }
}
