package com.fitclub.schedule.infrastructure.http;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.fitclub.common.http.InternalServiceClient;
import com.fitclub.schedule.application.port.TrainerDirectory;

/**
 * HTTP-адаптер порта TrainerDirectory: GET /internal/users/{id}/trainer-status в auth-service
 * с X-Service-Token направления schedule → auth. Таймауты, X-Request-ID, повтор безопасного GET
 * и перевод сбоев в 502/503/504 выполняет InternalServiceClient.
 */
public class AuthTrainerDirectory implements TrainerDirectory {

    private final InternalServiceClient auth;

    public AuthTrainerDirectory(InternalServiceClient auth) {
        this.auth = auth;
    }

    @Override
    public Optional<TrainerStatus> findTrainerStatus(UUID userId) {
        return auth.getOptional("/internal/users/{id}/trainer-status", TrainerStatusResponse.class,
                AuthTrainerDirectory::toStatus, userId);
    }

    private static TrainerStatus toStatus(TrainerStatusResponse body) {
        // Некорректный ответ (нет обязательных полей) — это 502, а не «тренер не найден»
        return new TrainerStatus(Objects.requireNonNull(body.userId()), Objects.requireNonNull(body.active()),
                Objects.requireNonNull(body.trainer()), Objects.requireNonNull(body.displayName()));
    }

    /** Контракт ответа auth-service (docs/api.md). */
    record TrainerStatusResponse(
            @JsonProperty("user_id") UUID userId,
            @JsonProperty("active") Boolean active,
            @JsonProperty("trainer") Boolean trainer,
            @JsonProperty("display_name") String displayName) {
    }
}
