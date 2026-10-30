-- schedule_db: типы тренировок и занятия. Владелец данных — только schedule-service.

CREATE TABLE training_types (
    id          UUID          PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    description VARCHAR(1000),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_training_types_name UNIQUE (name)
);

CREATE TABLE sessions (
    id                   UUID         PRIMARY KEY,
    training_type_id     UUID         NOT NULL REFERENCES training_types (id),
    -- Ссылка на пользователя auth-service: только UUID, без внешнего ключа между БД
    trainer_id           UUID         NOT NULL,
    -- Снимок имени тренера на момент назначения (получен из Auth), для отображения в UI
    trainer_display_name VARCHAR(100) NOT NULL,
    title                VARCHAR(150) NOT NULL,
    starts_at            TIMESTAMPTZ  NOT NULL,
    ends_at              TIMESTAMPTZ  NOT NULL,
    capacity             INTEGER      NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    published_at         TIMESTAMPTZ,
    cancelled_at         TIMESTAMPTZ,
    cancel_reason        VARCHAR(500),
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_sessions_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_sessions_capacity CHECK (capacity > 0),
    CONSTRAINT ck_sessions_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    CONSTRAINT ck_sessions_published_at CHECK (status = 'DRAFT' OR published_at IS NOT NULL OR cancelled_at IS NOT NULL),
    CONSTRAINT ck_sessions_cancelled_at CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE INDEX ix_sessions_starts_at ON sessions (starts_at);
CREATE INDEX ix_sessions_trainer ON sessions (trainer_id, starts_at);

-- Transactional outbox: событие сохраняется в той же транзакции, что и изменение занятия
CREATE TABLE outbox_events (
    id                UUID         PRIMARY KEY,
    event_type        VARCHAR(64)  NOT NULL,
    routing_key       VARCHAR(128) NOT NULL,
    aggregate_type    VARCHAR(64)  NOT NULL,
    aggregate_id      UUID         NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    envelope          JSONB        NOT NULL,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    published_at      TIMESTAMPTZ,
    attempts          INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL,
    last_error        VARCHAR(500)
);

CREATE INDEX ix_outbox_events_pending ON outbox_events (next_attempt_at) WHERE published_at IS NULL;
