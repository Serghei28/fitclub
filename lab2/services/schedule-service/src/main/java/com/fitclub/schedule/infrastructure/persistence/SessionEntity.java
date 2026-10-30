package com.fitclub.schedule.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.fitclub.schedule.domain.SessionStatus;

/** JPA-отображение sessions. trainer_id — UUID пользователя auth-service без внешнего ключа. */
@Entity
@Table(name = "sessions")
class SessionEntity {

    @Id
    UUID id;

    @Column(name = "training_type_id", nullable = false)
    UUID trainingTypeId;

    @Column(name = "trainer_id", nullable = false)
    UUID trainerId;

    @Column(name = "trainer_display_name", nullable = false, length = 100)
    String trainerDisplayName;

    @Column(nullable = false, length = 150)
    String title;

    @Column(name = "starts_at", nullable = false)
    Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    Instant endsAt;

    @Column(nullable = false)
    int capacity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    SessionStatus status;

    @Column(name = "published_at")
    Instant publishedAt;

    @Column(name = "cancelled_at")
    Instant cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    String cancelReason;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Version
    long version;
}
