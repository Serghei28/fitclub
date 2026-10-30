package com.fitclub.schedule.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fitclub.schedule.domain.TrainingType;

/** Порт хранения типов тренировок. */
public interface TrainingTypeRepository {

    Optional<TrainingType> findById(UUID id);

    List<TrainingType> findAllActive();

    /** Названия типов по id — для отображения в ответах API. */
    Map<UUID, String> findNames(Collection<UUID> ids);

    /** При занятом названии бросает ScheduleErrors.trainingTypeNameTaken() (в том числе при гонке). */
    void add(TrainingType type);
}
