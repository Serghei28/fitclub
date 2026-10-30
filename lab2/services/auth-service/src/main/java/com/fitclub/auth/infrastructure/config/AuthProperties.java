package com.fitclub.auth.infrastructure.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки auth-service, проверяются при запуске.
 *
 * @param jwtPrivateKey   закрытый RSA-ключ (PKCS#8 PEM); есть только у auth-service
 * @param accessTokenTtl  срок access JWT (по умолчанию 15 минут)
 * @param refreshTokenTtl срок refresh-токена (по умолчанию 7 дней)
 * @param initialAdmin    первый администратор; создаётся, только если заданы email и пароль
 */
@Validated
@ConfigurationProperties("fitclub.auth")
public record AuthProperties(
        @NotNull Resource jwtPrivateKey,
        @NotNull @DefaultValue("15m") Duration accessTokenTtl,
        @NotNull @DefaultValue("7d") Duration refreshTokenTtl,
        @Valid InitialAdmin initialAdmin) {

    public record InitialAdmin(String email, String password, @DefaultValue("Администратор") String displayName) {

        public boolean configured() {
            return email != null && !email.isBlank() && password != null && !password.isBlank();
        }

        @Override
        public String toString() {
            return "InitialAdmin[email=" + email + "]";
        }
    }
}
