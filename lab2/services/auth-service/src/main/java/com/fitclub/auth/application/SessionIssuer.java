package com.fitclub.auth.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fitclub.auth.application.port.AccessTokenIssuer;
import com.fitclub.auth.application.port.AccessTokenIssuer.IssuedAccessToken;
import com.fitclub.auth.application.port.RefreshTokenRepository;
import com.fitclub.auth.domain.RefreshToken;
import com.fitclub.auth.domain.User;

/** Выпуск пары access + refresh. Общий шаг для входа и ротации; вызывается внутри их транзакций. */
@Component
public class SessionIssuer {

    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenRepository refreshTokens;
    private final TokenSettings settings;

    public SessionIssuer(AccessTokenIssuer accessTokens, RefreshTokenRepository refreshTokens,
            TokenSettings settings) {
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.settings = settings;
    }

    /** Новая цепочка refresh-токенов (вход). */
    public TokenPair start(User user, Instant now) {
        return issue(user, UUID.randomUUID(), now).pair();
    }

    /** Ротация: старый токен помечается заменённым, новый продолжает ту же цепочку. */
    public TokenPair rotate(RefreshToken current, User user, Instant now) {
        Issued issued = issue(user, current.familyId(), now);
        current.rotateTo(issued.refreshTokenId(), now);
        refreshTokens.update(current);
        return issued.pair();
    }

    private Issued issue(User user, UUID familyId, Instant now) {
        String rawRefresh = RefreshTokenSecrets.generate();
        RefreshToken refresh = RefreshToken.issue(UUID.randomUUID(), user.id(), familyId,
                RefreshTokenSecrets.hash(rawRefresh), now, settings.refreshTokenTtl());
        refreshTokens.add(refresh);
        IssuedAccessToken access = accessTokens.issue(user, now);
        return new Issued(refresh.id(),
                new TokenPair(access.value(), access.expiresAt(), rawRefresh, refresh.expiresAt(), user));
    }

    private record Issued(UUID refreshTokenId, TokenPair pair) {
    }
}
