package com.fitclub.auth.application;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.auth.application.port.PasswordHasher;
import com.fitclub.auth.application.port.UserRepository;
import com.fitclub.auth.domain.AuthErrors;
import com.fitclub.auth.domain.Email;
import com.fitclub.auth.domain.PasswordPolicy;
import com.fitclub.auth.domain.User;

/** Публичная регистрация. Роль всегда CLIENT: в команде нет поля для ролей. */
@Service
public class RegisterUserUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterUserUseCase.class);

    private final UserRepository users;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public RegisterUserUseCase(UserRepository users, PasswordHasher passwordHasher, Clock clock) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    public record Command(String email, String password, String displayName) {

        @Override
        public String toString() {
            return "Command[displayName=" + displayName + "]";
        }
    }

    @Transactional
    public User register(Command command) {
        Email email = Email.of(command.email());
        PasswordPolicy.check(command.password());
        if (users.findByEmail(email).isPresent()) {
            throw AuthErrors.emailAlreadyUsed();
        }
        User user = User.register(UUID.randomUUID(), email, passwordHasher.hash(command.password()),
                command.displayName(), clock.instant());
        // Параллельная регистрация того же email упирается в uq_users_email: адаптер вернёт тот же EMAIL_ALREADY_USED
        users.add(user);
        log.info("User registered: user_id={}", user.id());
        return user;
    }
}
