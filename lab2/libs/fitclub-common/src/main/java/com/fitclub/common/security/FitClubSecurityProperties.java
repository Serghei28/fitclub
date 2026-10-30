package com.fitclub.common.security;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки проверки пользовательских JWT. Проверяются при запуске: без публичного ключа,
 * issuer и audience сервис не стартует.
 *
 * @param jwtPublicKey расположение публичного RSA-ключа (PEM, X.509), например file:/run/secrets/jwt_public_key.pem
 * @param issuer       ожидаемое значение claim iss
 * @param audience     ожидаемое значение claim aud
 * @param publicPaths  пути без аутентификации, кроме технических (/health, /ready, Swagger)
 */
@Validated
@ConfigurationProperties("fitclub.security")
public record FitClubSecurityProperties(
        @NotNull Resource jwtPublicKey,
        @NotBlank String issuer,
        @NotBlank String audience,
        List<String> publicPaths) {

    public FitClubSecurityProperties {
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    }
}
