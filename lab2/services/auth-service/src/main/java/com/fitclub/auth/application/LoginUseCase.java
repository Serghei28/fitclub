package com.fitclub.auth.application;

import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.auth.application.port.PasswordHasher;
import com.fitclub.auth.application.port.UserRepository;
import com.fitclub.auth.domain.AuthErrors;
import com.fitclub.auth.domain.Email;
import com.fitclub.auth.domain.User;
import com.fitclub.common.error.DomainException;

/**
 * Вход по email и паролю. Неизвестный email, неверный пароль и отключённая учётная запись дают
 * одинаковый ответ INVALID_CREDENTIALS; для неизвестного email пароль всё равно сверяется с фиктивным хешем,
 * чтобы время ответа не выдавало существование пользователя.
 */
@Service
public class LoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoginUseCase.class);

    private final UserRepository users;
    private final PasswordHasher passwordHasher;
    private final SessionIssuer sessions;
    private final Clock clock;
    private final String dummyHash;

    public LoginUseCase(UserRepository users, PasswordHasher passwordHasher, SessionIssuer sessions, Clock clock) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.sessions = sessions;
        this.clock = clock;
        this.dummyHash = passwordHasher.hash("timing-equalizer-password");
    }

    @Transactional
    public TokenPair login(String rawEmail, String rawPassword) {
        Optional<User> user = parseEmail(rawEmail).flatMap(users::findByEmail);
        if (user.isEmpty()) {
            passwordHasher.matches(rawPassword, dummyHash);
            log.info("Login failed: invalid credentials");
            throw AuthErrors.invalidCredentials();
        }
        if (!passwordHasher.matches(rawPassword, user.get().passwordHash()) || !user.get().isActive()) {
            log.info("Login failed: invalid credentials, user_id={}", user.get().id());
            throw AuthErrors.invalidCredentials();
        }
        TokenPair pair = sessions.start(user.get(), clock.instant());
        log.info("Login succeeded: user_id={}", user.get().id());
        return pair;
    }

    private static Optional<Email> parseEmail(String raw) {
        try {
            return Optional.of(Email.of(raw));
        } catch (DomainException invalidFormat) {
            return Optional.empty();
        }
    }
}
