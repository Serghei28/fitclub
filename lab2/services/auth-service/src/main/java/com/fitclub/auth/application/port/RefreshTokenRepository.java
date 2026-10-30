package com.fitclub.auth.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fitclub.auth.domain.RefreshToken;

/** Порт хранения refresh-токенов (только хеши). */
public interface RefreshTokenRepository {

    /** Находит токен и блокирует строку до конца транзакции: две параллельные ротации одного токена невозможны. */
    Optional<RefreshToken> findByHashForUpdate(String tokenHash);

    void add(RefreshToken token);

    void update(RefreshToken token);

    /** Отзывает все ещё действующие токены цепочки; возвращает их количество. */
    int revokeFamily(UUID familyId, Instant now);
}
