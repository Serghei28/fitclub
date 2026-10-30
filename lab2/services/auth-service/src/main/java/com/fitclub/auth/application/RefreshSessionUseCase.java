package com.fitclub.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.auth.application.port.RefreshTokenRepository;
import com.fitclub.auth.application.port.UserRepository;
import com.fitclub.auth.domain.AuthErrors;
import com.fitclub.auth.domain.RefreshToken;
import com.fitclub.auth.domain.User;
import com.fitclub.common.error.DomainException;

/**
 * Ротация refresh-токена: старый становится недействительным, выдаётся новая пара.
 * Повторное предъявление уже заменённого токена отзывает всю цепочку (защита от кражи токена).
 * Отзыв должен сохраниться даже при отказе, поэтому DomainException не откатывает транзакцию.
 */
@Service
public class RefreshSessionUseCase {

    private static final Logger log = LoggerFactory.getLogger(RefreshSessionUseCase.class);

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final SessionIssuer sessions;
    private final Clock clock;

    public RefreshSessionUseCase(RefreshTokenRepository refreshTokens, UserRepository users, SessionIssuer sessions,
            Clock clock) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = DomainException.class)
    public TokenPair refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw AuthErrors.invalidRefreshToken();
        }
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByHashForUpdate(RefreshTokenSecrets.hash(rawRefreshToken))
                .orElseThrow(AuthErrors::invalidRefreshToken);

        if (token.wasRotated()) {
            int revoked = refreshTokens.revokeFamily(token.familyId(), now);
            log.warn("Refresh token reuse detected: user_id={}, family revoked ({} active token(s))",
                    token.userId(), revoked);
            throw AuthErrors.invalidRefreshToken();
        }
        if (token.isRevoked() || token.isExpired(now)) {
            throw AuthErrors.invalidRefreshToken();
        }
        Optional<User> user = users.findById(token.userId()).filter(User::isActive);
        if (user.isEmpty()) {
            token.revoke(now);
            refreshTokens.update(token);
            throw AuthErrors.invalidRefreshToken();
        }
        // Роли берутся из БД заново: изменение ролей отражается в токене при ближайшем обновлении
        TokenPair pair = sessions.rotate(token, user.get(), now);
        log.info("Session refreshed: user_id={}", token.userId());
        return pair;
    }
}
