-- auth_db: учётные записи, роли и refresh-токены. Владелец данных — только auth-service.

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_email UNIQUE (email),
    -- email хранится только в нормализованном виде: уникальность не обходится регистром и пробелами
    CONSTRAINT ck_users_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT ck_users_display_name CHECK (length(btrim(display_name)) > 0)
);

CREATE TABLE roles (
    code        VARCHAR(20)  PRIMARY KEY,
    description VARCHAR(100) NOT NULL
);

INSERT INTO roles (code, description) VALUES
    ('CLIENT', 'Клиент клуба'),
    ('TRAINER', 'Тренер'),
    ('ADMIN', 'Администратор');

CREATE TABLE user_roles (
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_code  VARCHAR(20) NOT NULL REFERENCES roles (code),
    granted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, role_code)
);

CREATE INDEX ix_user_roles_role ON user_roles (role_code);

-- В БД хранится только SHA-256 от случайного refresh-токена, сам токен знает лишь клиент.
-- family_id объединяет цепочку ротаций: повторное использование старого токена отзывает всю цепочку.
CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   UUID        NOT NULL,
    token_hash  CHAR(64)    NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_period CHECK (expires_at > issued_at)
);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
