package com.fitclub.auth.infrastructure.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.fitclub.auth.application.port.PasswordHasher;
import com.fitclub.auth.domain.PasswordPolicy;

/** Адаптер порта PasswordHasher на стандартном Spring PasswordEncoder (BCrypt). */
@Component
class SpringPasswordHasher implements PasswordHasher {

    private final PasswordEncoder encoder;

    SpringPasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        // BCryptPasswordEncoder отклоняет пароли длиннее 72 байт исключением; для входа это просто «не совпало»
        if (rawPassword == null || !PasswordPolicy.fitsHashLimit(rawPassword)) {
            return false;
        }
        return encoder.matches(rawPassword, passwordHash);
    }
}
