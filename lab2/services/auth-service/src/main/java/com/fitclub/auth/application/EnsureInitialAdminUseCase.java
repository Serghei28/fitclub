package com.fitclub.auth.application;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.auth.application.port.PasswordHasher;
import com.fitclub.auth.application.port.UserRepository;
import com.fitclub.auth.domain.Email;
import com.fitclub.auth.domain.PasswordPolicy;
import com.fitclub.auth.domain.Role;
import com.fitclub.auth.domain.User;

/**
 * Создание первого администратора из явно заданной конфигурации (email + секрет с паролем).
 * Идемпотентно: если администратор уже есть, ничего не меняется; повторный запуск не создаёт дублей.
 */
@Service
public class EnsureInitialAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(EnsureInitialAdminUseCase.class);

    private final UserRepository users;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public EnsureInitialAdminUseCase(UserRepository users, PasswordHasher passwordHasher, Clock clock) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    public enum Result { CREATED, PROMOTED, ALREADY_PRESENT }

    @Transactional
    public Result ensure(String rawEmail, String rawPassword, String displayName) {
        if (users.existsWithRole(Role.ADMIN)) {
            return Result.ALREADY_PRESENT;
        }
        Email email = Email.of(rawEmail);
        Optional<User> existing = users.findByEmail(email);
        if (existing.isPresent()) {
            // Учётная запись с этим email уже есть (например, зарегистрирована вручную): только добавляем ADMIN
            User user = existing.get();
            EnumSet<Role> roles = EnumSet.copyOf(user.roles());
            roles.add(Role.ADMIN);
            user.replaceRoles(roles, clock.instant());
            users.update(user);
            log.info("Initial admin: existing user promoted, user_id={}", user.id());
            return Result.PROMOTED;
        }
        PasswordPolicy.check(rawPassword);
        User admin = User.administrator(UUID.randomUUID(), email, passwordHasher.hash(rawPassword), displayName,
                clock.instant());
        users.add(admin);
        log.info("Initial admin created: user_id={}", admin.id());
        return Result.CREATED;
    }
}
