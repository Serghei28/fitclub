package com.fitclub.auth.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fitclub.auth.application.TokenPair;
import com.fitclub.auth.application.UserQueryService.TrainerStatus;
import com.fitclub.auth.domain.Role;
import com.fitclub.auth.domain.User;

/**
 * Request/response DTO auth-service. JPA-сущности и доменные объекты наружу не сериализуются;
 * password_hash и хеши refresh-токенов в ответы не попадают.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    @Schema(description = "Регистрация. Поля ролей нет: всегда назначается CLIENT.")
    public record RegisterRequest(
            @NotBlank @Size(max = 254) @Schema(example = "anna@example.com") String email,
            @NotBlank @Size(max = 200) @Schema(example = "S3cure-pass") String password,
            @NotBlank @Size(max = 100) @Schema(example = "Анна Петрова") String displayName) {

        @Override
        public String toString() {
            return "RegisterRequest[displayName=" + displayName + "]";
        }
    }

    public record LoginRequest(
            @NotBlank @Size(max = 254) @Schema(example = "anna@example.com") String email,
            @NotBlank @Size(max = 200) String password) {

        @Override
        public String toString() {
            return "LoginRequest[]";
        }
    }

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[]";
        }
    }

    public record ChangeRolesRequest(
            @NotNull @NotEmpty @Schema(example = "[\"CLIENT\", \"TRAINER\"]") Set<Role> roles) {
    }

    public record UserResponse(UUID id, String email, String displayName, List<Role> roles, boolean active,
            Instant createdAt) {

        public static UserResponse from(User user) {
            return new UserResponse(user.id(), user.email().value(), user.displayName(),
                    user.roles().stream().sorted().toList(), user.isActive(), user.createdAt());
        }
    }

    @Schema(description = "Токены храните только в памяти вкладки: не в localStorage, URL или логах.")
    public record TokenResponse(String tokenType, String accessToken, long expiresIn, Instant accessTokenExpiresAt,
            String refreshToken, Instant refreshTokenExpiresAt, UserResponse user) {

        public static TokenResponse from(TokenPair pair, Instant now) {
            long expiresIn = Math.max(0, pair.accessTokenExpiresAt().getEpochSecond() - now.getEpochSecond());
            return new TokenResponse("Bearer", pair.accessToken(), expiresIn, pair.accessTokenExpiresAt(),
                    pair.refreshToken(), pair.refreshTokenExpiresAt(), UserResponse.from(pair.user()));
        }

        @Override
        public String toString() {
            return "TokenResponse[user=" + user.id() + "]";
        }
    }

    public record TrainerStatusResponse(UUID userId, boolean active, boolean trainer, String displayName) {

        public static TrainerStatusResponse from(TrainerStatus status) {
            return new TrainerStatusResponse(status.userId(), status.active(), status.trainer(),
                    status.displayName());
        }
    }
}
