-- membership_db: тарифы, заказы и абонементы. Владелец данных — только membership-service.

CREATE TABLE plans (
    id            UUID         PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    duration_days INTEGER      NOT NULL,
    -- NULL = безлимитный тариф
    visit_limit   INTEGER,
    -- Деньги — целое число минимальных единиц (бани для MDL), без float/double
    price_minor   BIGINT       NOT NULL,
    currency      CHAR(3)      NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_plans_name UNIQUE (name),
    CONSTRAINT ck_plans_duration CHECK (duration_days > 0),
    CONSTRAINT ck_plans_visit_limit CHECK (visit_limit IS NULL OR visit_limit > 0),
    CONSTRAINT ck_plans_price CHECK (price_minor >= 0),
    CONSTRAINT ck_plans_currency CHECK (currency ~ '^[A-Z]{3}$')
);

-- Заказ хранит неизменяемый снимок тарифа: позднее изменение тарифа не меняет заказ
CREATE TABLE membership_orders (
    id            UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL,
    plan_id       UUID         NOT NULL REFERENCES plans (id),
    plan_name     VARCHAR(100) NOT NULL,
    duration_days INTEGER      NOT NULL,
    visit_limit   INTEGER,
    price_minor   BIGINT       NOT NULL,
    currency      CHAR(3)      NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    payment_id    UUID,
    paid_at       TIMESTAMPTZ,
    failed_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_orders_status CHECK (status IN ('AWAITING_PAYMENT', 'PAID', 'PAYMENT_FAILED')),
    CONSTRAINT ck_orders_paid CHECK ((status = 'PAID') = (paid_at IS NOT NULL AND payment_id IS NOT NULL)),
    CONSTRAINT ck_orders_duration CHECK (duration_days > 0),
    CONSTRAINT ck_orders_visit_limit CHECK (visit_limit IS NULL OR visit_limit > 0),
    CONSTRAINT ck_orders_price CHECK (price_minor >= 0)
);

-- Не более одного ожидающего оплаты заказа на пользователя, в том числе при параллельных запросах
CREATE UNIQUE INDEX uq_orders_one_pending_per_user ON membership_orders (user_id) WHERE status = 'AWAITING_PAYMENT';
CREATE INDEX ix_orders_user ON membership_orders (user_id, created_at DESC);

-- Абонемент действует на интервале [starts_at, ends_at)
CREATE TABLE memberships (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL,
    order_id    UUID         NOT NULL REFERENCES membership_orders (id),
    payment_id  UUID         NOT NULL,
    plan_name   VARCHAR(100) NOT NULL,
    visit_limit INTEGER,
    starts_at   TIMESTAMPTZ  NOT NULL,
    ends_at     TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    -- Один заказ и одна оплата дают ровно один абонемент, даже при повторной доставке события
    CONSTRAINT uq_memberships_order UNIQUE (order_id),
    CONSTRAINT uq_memberships_payment UNIQUE (payment_id),
    CONSTRAINT ck_memberships_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_memberships_visit_limit CHECK (visit_limit IS NULL OR visit_limit > 0)
);

CREATE INDEX ix_memberships_user ON memberships (user_id, ends_at);

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

-- Идемпотентность потребителя: event_id уже обработанных событий
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);

-- Idempotency-Key для POST /api/membership-orders
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
