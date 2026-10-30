package com.fitclub.schedule.domain;

import java.time.Instant;
import java.util.UUID;

/** Тип тренировки (йога, пилатес, …). Управляет ADMIN. */
public record TrainingType(UUID id, String name, String description, boolean active, Instant createdAt) {

    public static final int MAX_NAME_LENGTH = 100;

    public static TrainingType create(UUID id, String name, String description, Instant now) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH) {
            throw ScheduleErrors.invalidTrainingTypeName();
        }
        String text = description == null || description.isBlank() ? null : description.strip();
        return new TrainingType(id, normalized, text, true, now);
    }
}
