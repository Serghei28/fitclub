package com.fitclub.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Строка user_roles. Равенство только по коду роли: момент выдачи сохраняется, пока роль остаётся. */
@Embeddable
class UserRoleEmbeddable {

    @Column(name = "role_code", nullable = false, length = 20)
    String roleCode;

    @Column(name = "granted_at", nullable = false)
    Instant grantedAt;

    protected UserRoleEmbeddable() {
    }

    UserRoleEmbeddable(String roleCode, Instant grantedAt) {
        this.roleCode = roleCode;
        this.grantedAt = grantedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UserRoleEmbeddable that && Objects.equals(roleCode, that.roleCode);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(roleCode);
    }
}
