package com.fitclub.common.security;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Разрешённые вызывающие сервисы для внутренних маршрутов /internal/**.
 * У каждого направления (например, payment-service → membership-service) свой секрет
 * и свой список разрешённых путей. Сервис без внутреннего API оставляет список пустым.
 */
@Validated
@ConfigurationProperties("fitclub.internal")
public record InternalAuthProperties(List<@Valid Client> clients) {

    public InternalAuthProperties {
        clients = clients == null ? List.of() : List.copyOf(clients);
    }

    /**
     * @param name  имя вызывающего сервиса, попадает в лог и в authority SERVICE_{name}
     * @param token секрет направления; передаётся в X-Service-Token
     * @param paths шаблоны путей, разрешённых этому вызывающему (PathPattern, например /internal/memberships/**)
     */
    public record Client(
            @NotBlank String name,
            @NotBlank @Size(min = 32, message = "service token must be at least 32 characters") String token,
            @NotEmpty List<@NotBlank String> paths) {

        @Override
        public String toString() {
            return "Client[name=" + name + ", paths=" + paths + "]";
        }
    }
}
