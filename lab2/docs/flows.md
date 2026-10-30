# Сценарии: REST, события и согласованность

> Статус: оплата и активация абонемента реализованы и проверены (шаг 7). Сценарии бронирования и отмены дополняются на шагах 8–9.

## Оплата и активация абонемента

```mermaid
sequenceDiagram
    autonumber
    actor C as Клиент (UI)
    participant GW as Gateway
    participant M as membership-service
    participant P as payment-service
    participant MQ as RabbitMQ
    participant N as notification-service

    C->>GW: POST /api/membership-orders {plan_id} + Idempotency-Key
    GW->>M: (JWT проверен)
    M-->>C: 201 заказ AWAITING_PAYMENT (цена — снимок тарифа)

    C->>GW: POST /api/payments {membership_order_id} + Idempotency-Key
    GW->>P: (JWT проверен)
    P->>M: GET /internal/membership-orders/{id}/payment-details (X-Service-Token, таймауты)
    M-->>P: user_id, status, amount_minor, currency
    P-->>C: 201 платёж PENDING (сумма с сервера, владелец сверен)

    C->>GW: POST /api/payments/{id}/simulate {SUCCESS} + Idempotency-Key  (только demo)
    P->>P: BEGIN; payments → SUCCEEDED; INSERT outbox PaymentSucceeded; COMMIT
    P-->>C: 200 SUCCEEDED
    Note over C: UI показывает «активация…» и опрашивает /api/memberships/me

    P--)MQ: relay: PaymentSucceeded (confirm + без возврата)
    MQ--)M: очередь membership-service.events
    M->>M: BEGIN; processed_events(event_id); проверка владельца, суммы, валюты;<br/>заказ → PAID; INSERT memberships; INSERT outbox MembershipActivated; COMMIT; ACK
    M--)MQ: relay: MembershipActivated
    MQ--)N: очередь notification-service.events (уведомление — шаг 9)
    C->>GW: GET /api/memberships/me
    M-->>C: ACTIVE, [starts_at = момент оплаты, ends_at = +30 дней)
```

### Где какая транзакция

| Шаг | Сервис | Что фиксируется вместе |
|---|---|---|
| Заказ | membership | заказ + запись `idempotency_records` (под блокировкой пользователя) |
| Платёж | payment | платёж `PENDING` + `idempotency_records`. Чтение реквизитов из Membership — **до** транзакции |
| Тестовое завершение | payment | статус платежа + событие в `outbox_events` + `idempotency_records` |
| Активация | membership | `processed_events` + заказ `PAID` + абонемент + `MembershipActivated` в outbox |

Общей распределённой транзакции нет. Payment и Membership согласуются через событие: **eventual consistency**. Между ответом «платёж SUCCEEDED» и появлением абонемента проходит обычно меньше секунды (в Docker-проверке абонемент был виден сразу).

### Что происходит при сбоях

| Сбой | Поведение |
|---|---|
| Membership недоступен при создании платежа | Платёж не создаётся: `503`/`504`. Придуманный «успешный» ответ невозможен |
| RabbitMQ недоступен после оплаты | Платёж `SUCCEEDED` уже сохранён, событие ждёт в outbox payment. После восстановления брокера relay его отправит. Пока событие не отправлено, абонемент не активирован. UI показывает «активация в процессе» |
| membership-service остановлен | `PaymentSucceeded` ждёт в durable-очереди `membership-service.events`. После запуска Membership обработает его |
| Повторная доставка `PaymentSucceeded` (тот же `event_id`) | `processed_events` → пропуск, второй абонемент не создаётся |
| Новое событие о том же платеже (другой `event_id`) | Заказ уже `PAID` с этим `payment_id` → `ALREADY_APPLIED`, срок не продлевается |
| Сумма/валюта/владелец не совпадают со снимком заказа | Абонемент не создаётся, сообщение уходит в `membership-service.events.dlq` для ручного разбора |
| Испорченное сообщение (не JSON) | Сразу в DLQ, очередь не блокируется: следующее событие обрабатывается |
| `PaymentDeclined` | Заказ `PAYMENT_FAILED`, абонемента нет, можно сделать новый заказ |
| `PaymentDeclined` пришёл после `PaymentSucceeded` | Игнорируется: заказ уже `PAID`, абонемент сохраняется |
| Повтор `POST /api/payments` с другим ключом | Возвращается тот же платёж: по заказу не больше одного платежа (`uq_payments_order`) |
| Повтор тестового завершения с тем же ключом | Тот же ответ, второго события нет |
| Попытка сменить `SUCCEEDED` на `DECLINED` | `409 PAYMENT_ALREADY_COMPLETED` |

Все строки таблицы, кроме остановки сервисов в Docker, проверены автотестами `PaymentApiIT`, `PaymentEventsIT` и `PaymentRulesTest`. Остановка Membership и брокера в сквозном сценарии — шаг 11.
