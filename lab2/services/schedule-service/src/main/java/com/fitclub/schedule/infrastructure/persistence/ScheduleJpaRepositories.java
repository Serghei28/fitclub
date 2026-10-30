package com.fitclub.schedule.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface TrainingTypeJpaRepository extends JpaRepository<TrainingTypeEntity, UUID> {

    List<TrainingTypeEntity> findByActiveTrueOrderByNameAsc();

    List<TrainingTypeEntity> findByIdIn(Collection<UUID> ids);
}

interface SessionJpaRepository extends JpaRepository<SessionEntity, UUID>, JpaSpecificationExecutor<SessionEntity> {
}
