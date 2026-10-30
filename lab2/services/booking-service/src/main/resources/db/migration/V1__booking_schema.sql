-- booking_db: проекция занятий, бронирования и учёт посещений. Владелец данных — только booking-service.

-- Проекция событий SessionPublished/SessionCancelled из schedule-service.
-- capacity — неизменяемое условие занятия от Schedule; booked_count считает и меняет только Booking.
-- Строка может появиться как «надгробие» (status = CANCELLED без описания), если отмена пришла раньше публикации:
-- тогда устаревшее SessionPublished уже не откроет запись (сравнение aggregate_version).
CREATE TABLE session_inventory (
    session_id           UUID         PRIMARY KEY,
    title                VARCHAR(150),
    trainer_id           UUID,
    trainer_display_name VARCHAR(100),
    starts_at            TIMESTAMPTZ,
    ends_at              TIMESTAMPTZ,
    capacity             INTEGER,
    booked_count         INTEGER      NOT NULL DEFAULT 0,
    status               VARCHAR(16)  NOT NULL,
    aggregate_version    BIGINT       NOT NULL,
    cancelled_at         TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_inventory_status CHECK (status IN ('OPEN', 'CANCELLED')),
    CONSTRAINT ck_inventory_open_has_snapshot CHECK (
        status = 'CANCELLED'
        OR (title IS NOT NULL AND trainer_id IS NOT NULL AND starts_at IS NOT NULL
            AND ends_at IS NOT NULL AND capacity IS NOT NULL)),
    CONSTRAINT ck_inventory_capacity CHECK (capacity IS NULL OR capacity > 0),
    -- Инвариант вместимости проверяет и сама БД: мест не может стать больше capacity
    CONSTRAINT ck_inventory_booked CHECK (booked_count >= 0 AND (capacity IS NULL OR booked_count <= capacity))
);

CREATE INDEX ix_inventory_trainer ON session_inventory (trainer_id);

CREATE TABLE bookings (
    id            UUID        PRIMARY KEY,
    session_id    UUID        NOT NULL REFERENCES session_inventory (session_id),
    user_id       UUID        NOT NULL,
    membership_id UUID        NOT NULL,
    status        VARCHAR(16) NOT NULL,
    cancel_reason VARCHAR(32),
    created_at    TIMESTAMPTZ NOT NULL,
    cancelled_at  TIMESTAMPTZ,
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_bookings_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_bookings_cancel_reason CHECK (cancel_reason IS NULL OR cancel_reason IN ('USER', 'SESSION_CANCELLED')),
    CONSTRAINT ck_bookings_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL))
);

-- У пользователя не больше одной действующей записи на занятие
CREATE UNIQUE INDEX uq_bookings_active_per_user_session ON bookings (session_id, user_id) WHERE status = 'CONFIRMED';
CREATE INDEX ix_bookings_user ON bookings (user_id, created_at DESC);
CREATE INDEX ix_bookings_session ON bookings (session_id) WHERE status = 'CONFIRMED';

-- Авторитетный учёт посещений абонемента: зарезервированные будущие и прошедшие подтверждённые записи.
-- visit_limit = NULL — безлимитный абонемент.
CREATE TABLE membership_usage (
    membership_id UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL,
    visit_limit   INTEGER,
    used_count    INTEGER     NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_usage_limit CHECK (visit_limit IS NULL OR visit_limit > 0),
    CONSTRAINT ck_usage_count CHECK (used_count >= 0 AND (visit_limit IS NULL OR used_count <= visit_limit))
);

CREATE INDEX ix_usage_user ON membership_usage (user_id);

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

CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);

-- Idempotency-Key для POST /api/bookings
CREATE TABLE idempotency_records (
    owner_id        UUID        NOT NULL,
    operation       VARCHAR(64) NOT NULL,
    idempotency_key UUID        NOT NULL,
    request_hash    CHAR(64)    NOT NULL,
    resource_id     UUID,
    response_status INTEGER     NOT NULL,
    response_body   JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_id, operation, idempotency_key)
);
