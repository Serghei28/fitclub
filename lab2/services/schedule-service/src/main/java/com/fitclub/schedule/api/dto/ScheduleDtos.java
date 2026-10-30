package com.fitclub.schedule.api.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fitclub.schedule.application.ScheduleQueryService.SessionView;
import com.fitclub.schedule.domain.Session;
import com.fitclub.schedule.domain.SessionStatus;
import com.fitclub.schedule.domain.TrainingType;

/** Request/response DTO schedule-service. Время — ISO 8601 с часовым поясом (UTC в ответах). */
public final class ScheduleDtos {

    private ScheduleDtos() {
    }

    public record CreateTrainingTypeRequest(
            @NotBlank @Size(max = 100) @Schema(example = "Йога") String name,
            @Size(max = 1000) String description) {
    }

    public record TrainingTypeResponse(UUID id, String name, String description, boolean active) {

        public static TrainingTypeResponse from(TrainingType type) {
            return new TrainingTypeResponse(type.id(), type.name(), type.description(), type.active());
        }
    }

    public record CreateSessionRequest(
            @NotNull UUID trainingTypeId,
            @NotNull @Schema(description = "UUID пользователя с ролью TRAINER (проверяется в auth-service)")
            UUID trainerId,
            @NotBlank @Size(max = 150) @Schema(example = "Утренняя йога") String title,
            @NotNull @Schema(example = "2026-11-10T07:00:00Z") Instant startsAt,
            @NotNull @Schema(example = "2026-11-10T08:00:00Z") Instant endsAt,
            @NotNull @Min(1) @Max(500) Integer capacity) {
    }

    @Schema(description = "Изменение черновика: передавайте только меняющиеся поля. Опубликованное занятие не меняется.")
    public record UpdateSessionRequest(
            UUID trainingTypeId,
            UUID trainerId,
            @Size(min = 1, max = 150) String title,
            Instant startsAt,
            Instant endsAt,
            @Min(1) @Max(500) Integer capacity) {
    }

    public record CancelSessionRequest(@Size(max = 500) String reason) {
    }

    @Schema(description = "Занятие. Свободные места — в booking-service: GET /api/availability")
    public record SessionResponse(UUID id, UUID trainingTypeId, String trainingTypeName, String title,
            UUID trainerId, String trainerDisplayName, Instant startsAt, Instant endsAt, int capacity,
            SessionStatus status, boolean editable, Instant publishedAt, Instant cancelledAt, String cancelReason,
            long version) {

        public static SessionResponse from(SessionView view) {
            return from(view.session(), view.trainingTypeName());
        }

        public static SessionResponse from(Session s, String trainingTypeName) {
            return new SessionResponse(s.id(), s.trainingTypeId(), trainingTypeName, s.title(), s.trainerId(),
                    s.trainerDisplayName(), s.startsAt(), s.endsAt(), s.capacity(), s.status(),
                    s.status() == SessionStatus.DRAFT, s.publishedAt(), s.cancelledAt(), s.cancelReason(),
                    s.version());
        }
    }
}
