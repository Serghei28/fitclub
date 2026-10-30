package com.fitclub.schedule.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "training_types")
class TrainingTypeEntity {

    @Id
    UUID id;

    @Column(nullable = false, length = 100)
    String name;

    @Column(length = 1000)
    String description;

    @Column(nullable = false)
    boolean active;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;
}
