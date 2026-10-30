package com.fitclub.auth.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Учётная запись с минимальным профилем. Не зависит от JPA: сущность хранения живёт в infrastructure. */
public final class User {

    public static final int DISPLAY_NAME_MAX_LENGTH = 100;

    private final UUID id;
    private final Email email;
    private final String passwordHash;
    private final String displayName;
    private final boolean active;
    private final Set<Role> roles;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private User(UUID id, Email email, String passwordHash, String displayName, boolean active, Set<Role> roles,
            Instant createdAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.email = Objects.requireNonNull(email);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.displayName = displayName;
        this.active = active;
        this.roles = EnumSet.copyOf(roles);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /** Публичная регистрация: всегда только CLIENT, независимо от того, что прислал клиент. */
    public static User register(UUID id, Email email, String passwordHash, String displayName, Instant now) {
        return new User(id, email, passwordHash, validDisplayName(displayName), true, EnumSet.of(Role.CLIENT),
                now, now, 0);
    }

    /** Первый администратор, создаваемый явным bootstrap-действием из конфигурации. */
    public static User administrator(UUID id, Email email, String passwordHash, String displayName, Instant now) {
        return new User(id, email, passwordHash, validDisplayName(displayName), true, EnumSet.of(Role.ADMIN),
                now, now, 0);
    }

    /** Восстановление из хранилища. */
    public static User restore(UUID id, Email email, String passwordHash, String displayName, boolean active,
            Set<Role> roles, Instant createdAt, Instant updatedAt, long version) {
        return new User(id, email, passwordHash, displayName, active, roles.isEmpty() ? EnumSet.noneOf(Role.class)
                : roles, createdAt, updatedAt, version);
    }

    /** Замена набора ролей администратором. */
    public void replaceRoles(Set<Role> newRoles, Instant now) {
        if (newRoles == null || newRoles.isEmpty()) {
            throw AuthErrors.rolesRequired();
        }
        roles.clear();
        roles.addAll(newRoles);
        updatedAt = now;
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public boolean isActiveTrainer() {
        return active && roles.contains(Role.TRAINER);
    }

    private static String validDisplayName(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw AuthErrors.invalidDisplayName(DISPLAY_NAME_MAX_LENGTH);
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public Email email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isActive() {
        return active;
    }

    public Set<Role> roles() {
        return Set.copyOf(roles);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
