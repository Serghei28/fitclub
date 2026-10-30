package com.fitclub.schedule.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Узкий порт: только проверка тренера. Реализация — HTTP-клиент внутреннего API auth-service.
 * Сбой зависимости выражается ApiException 502/503/504; придуманный «успешный» ответ недопустим.
 */
public interface TrainerDirectory {

    /** Пустой Optional — пользователя нет. */
    Optional<TrainerStatus> findTrainerStatus(UUID userId);

    record TrainerStatus(UUID userId, boolean active, boolean trainer, String displayName) {

        public boolean eligible() {
            return active && trainer;
        }
    }
}
