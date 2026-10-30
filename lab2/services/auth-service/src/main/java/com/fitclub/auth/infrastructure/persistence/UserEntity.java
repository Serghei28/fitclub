package com.fitclub.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** JPA-отображение таблиц users и user_roles. Вне infrastructure не используется. */
@Entity
@Table(name = "users")
class UserEntity {

    @Id
    UUID id;

    @Column(nullable = false, length = 254)
    String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    String displayName;

    @Column(nullable = false)
    boolean active;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    Set<UserRoleEmbeddable> roles = new HashSet<>();

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Version
    long version;
}
