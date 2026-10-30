-- payment_db: платежи симулятора и идемпотентность. Владелец данных — только payment-service.

CREATE TABLE payments (
    id                  UUID         PRIMARY KEY,
    -- Ссылка на заказ membership-service: только UUID, без внешнего ключа между БД
    membership_order_id UUID         NOT NULL,
    user_id             UUID         NOT NULL,
    -- Сумма и валюта получены с сервера (снимок заказа Membership), а не от клиента
    amount_minor        BIGINT       NOT NULL,
    currency            CHAR(3)      NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    provider            VARCHAR(32)  NOT NULL,
    provider_reference  VARCHAR(100),
    decline_reason      VARCHAR(200),
    created_at          TIMESTAMPTZ  NOT NULL,
    completed_at        TIMESTAMPTZ,
    version             BIGINT       NOT NULL DEFAULT 0,
    -- Один заказ оплачивается максимум одним платежом, независимо от Idempotency-Key
    CONSTRAINT uq_payments_order UNIQUE (membership_order_id),
    CONSTRAINT ck_payments_amount CHECK (amount_minor >= 0),
    CONSTRAINT ck_payments_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'DECLINED')),
    CONSTRAINT ck_payments_completed CHECK ((status = 'PENDING') = (completed_at IS NULL))
);

CREATE INDEX ix_payments_user ON payments (user_id, created_at DESC);

-- Idempotency-Key для POST /api/payments и POST /api/payments/{id}/simulate
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
