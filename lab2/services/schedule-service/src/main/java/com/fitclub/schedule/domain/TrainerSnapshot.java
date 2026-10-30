package com.fitclub.schedule.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Снимок тренера на момент проверки в auth-service: UUID (ссылка без внешнего ключа) и отображаемое имя.
 * Имя хранится как снимок для UI; редактируется только в Auth.
 */
public record TrainerSnapshot(UUID trainerId, String displayName) {

    public TrainerSnapshot {
        Objects.requireNonNull(trainerId);
        Objects.requireNonNull(displayName);
    }
}
