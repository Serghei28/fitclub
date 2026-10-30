package com.fitclub.schedule.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import com.fitclub.schedule.application.port.TrainingTypeRepository;
import com.fitclub.schedule.domain.ScheduleErrors;
import com.fitclub.schedule.domain.TrainingType;

@Repository
class JpaTrainingTypeRepository implements TrainingTypeRepository {

    private final TrainingTypeJpaRepository jpa;

    JpaTrainingTypeRepository(TrainingTypeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<TrainingType> findById(UUID id) {
        return jpa.findById(id).map(JpaTrainingTypeRepository::toDomain);
    }

    @Override
    public List<TrainingType> findAllActive() {
        return jpa.findByActiveTrueOrderByNameAsc().stream().map(JpaTrainingTypeRepository::toDomain).toList();
    }

    @Override
    public Map<UUID, String> findNames(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jpa.findByIdIn(ids).stream().collect(Collectors.toMap(e -> e.id, e -> e.name));
    }

    @Override
    public void add(TrainingType type) {
        TrainingTypeEntity entity = new TrainingTypeEntity();
        entity.id = type.id();
        entity.name = type.name();
        entity.description = type.description();
        entity.active = type.active();
        entity.createdAt = type.createdAt();
        try {
            jpa.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_training_types_name")) {
                throw ScheduleErrors.trainingTypeNameTaken();
            }
            throw e;
        }
    }

    private static TrainingType toDomain(TrainingTypeEntity e) {
        return new TrainingType(e.id, e.name, e.description, e.active, e.createdAt);
    }
}
