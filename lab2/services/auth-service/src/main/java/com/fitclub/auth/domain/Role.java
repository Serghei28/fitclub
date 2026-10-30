package com.fitclub.auth.domain;

/** Роли пользователей клуба. При публичной регистрации назначается только CLIENT. */
public enum Role {
    CLIENT,
    TRAINER,
    ADMIN
}
