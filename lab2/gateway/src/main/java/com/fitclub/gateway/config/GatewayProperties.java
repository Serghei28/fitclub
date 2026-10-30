package com.fitclub.gateway.config;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки Gateway, проверяются при запуске. Адреса сервисов задаются только конфигурацией:
 * localhost-порты для запуска из IDE и имена контейнеров в профиле docker.
 */
@Validated
@ConfigurationProperties("fitclub.gateway")
public record GatewayProperties(@NotNull @Valid Services services, @NotNull @Valid Jwt jwt) {

    public record Services(
            @NotNull URI auth,
            @NotNull URI schedule,
            @NotNull URI membership,
            @NotNull URI booking,
            @NotNull URI payment,
            @NotNull URI notification) {
    }

    /** Те же iss, aud и публичный ключ, по которым токен проверяют сами сервисы. */
    public record Jwt(@NotNull Resource publicKey, @NotBlank String issuer, @NotBlank String audience) {
    }
}
