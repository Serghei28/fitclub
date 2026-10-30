package com.fitclub.schedule.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fitclub.schedule.application.port.TrainerDirectory;
import com.fitclub.schedule.domain.ScheduleErrors;
import com.fitclub.schedule.domain.TrainerSnapshot;

/** Проверка, что назначаемый пользователь — действующий TRAINER, и получение снимка его имени. */
@Component
public class TrainerVerification {

    private static final Logger log = LoggerFactory.getLogger(TrainerVerification.class);

    private final TrainerDirectory directory;

    public TrainerVerification(TrainerDirectory directory) {
        this.directory = directory;
    }

    /** Вызывать вне транзакции: это сетевой запрос в auth-service. */
    public TrainerSnapshot verify(UUID trainerId) {
        TrainerDirectory.TrainerStatus status = directory.findTrainerStatus(trainerId)
                .filter(TrainerDirectory.TrainerStatus::eligible)
                .orElseThrow(() -> {
                    log.info("Trainer rejected: trainer_id={}", trainerId);
                    return ScheduleErrors.trainerNotEligible();
                });
        return new TrainerSnapshot(status.userId(), status.displayName());
    }
}
