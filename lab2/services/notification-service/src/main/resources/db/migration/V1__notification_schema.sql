-- notification_db: уведомления, напоминания и проекция бронирований. Владелец данных — только notification-service.

CREATE TABLE notifications (
    id              UUID          PRIMARY KEY,
    user_id         UUID          NOT NULL,
    type            VARCHAR(40)   NOT NULL,
    title           VARCHAR(150)  NOT NULL,
    body            VARCHAR(1000) NOT NULL,
    source_event_id UUID,
    -- Ключ дедупликации (например, BOOKING_CONFIRMED:<booking_id>:<version>): повтор события не создаёт дубль
    dedup_key       VARCHAR(200)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    read_at         TIMESTAMPTZ,
    CONSTRAINT uq_notifications_dedup UNIQUE (dedup_key)
);

CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

-- Проекция последнего известного состояния бронирования (с версией).
-- Отменённое бронирование остаётся строкой-«надгробием»: устаревший BookingConfirmed его не «оживит».
CREATE TABLE booking_projection (
    booking_id      UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL,
    session_id      UUID,
    session_title   VARCHAR(150),
    starts_at       TIMESTAMPTZ,
    status          VARCHAR(16)  NOT NULL,
    booking_version BIGINT       NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_projection_status CHECK (status IN ('CONFIRMED', 'CANCELLED'))
);

-- Сохраняемые задачи напоминаний: переживают перезапуск сервиса.
-- locked_until защищает от двойного выполнения при параллельной обработке.
CREATE TABLE reminder_jobs (
    id            UUID         PRIMARY KEY,
    booking_id    UUID         NOT NULL,
    user_id       UUID         NOT NULL,
    session_title VARCHAR(150) NOT NULL,
    starts_at     TIMESTAMPTZ  NOT NULL,
    remind_at     TIMESTAMPTZ  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    locked_until  TIMESTAMPTZ,
    attempts      INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL,
    processed_at  TIMESTAMPTZ,
    CONSTRAINT uq_reminder_jobs_booking UNIQUE (booking_id),
    CONSTRAINT ck_reminder_jobs_status CHECK (status IN ('PENDING', 'SENT', 'CANCELLED', 'SKIPPED')),
    CONSTRAINT ck_reminder_jobs_time CHECK (remind_at <= starts_at)
);

CREATE INDEX ix_reminder_jobs_due ON reminder_jobs (remind_at) WHERE status = 'PENDING';

CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
