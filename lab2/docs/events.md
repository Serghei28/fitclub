# События: контракты v1

> Статус: контракт согласован на этапе 1, топология брокера уже создаётся инфраструктурой. Издатели, потребители, outbox, retry и DLQ реализуются на этапах 5–9, тогда же этот документ дополняется фактическими деталями.

## Конверт события

Все события передаются в одном JSON-конверте (`content-type: application/json`, persistent delivery):

```json
{
  "event_id": "7d0f3c3e-6a51-4a8e-9a0e-2a4f3a1c9b10",
  "event_type": "PaymentSucceeded",
  "schema_version": 1,
  "occurred_at": "2026-10-30T10:15:30.123Z",
  "producer": "payment-service",
  "aggregate_id": "5b1f...",
  "aggregate_version": 2,
  "correlation_id": "c1a2...",
  "causation_id": "9e8d...",
  "payload": { }
}
```

| Поле | Правило |
|---|---|
| `event_id` | UUID нового факта. При повторной отправке того же факта из outbox **сохраняется**, по нему потребитель распознаёт дубликат |
| `event_type` | Имя в PascalCase, совпадает с таблицей ниже |
| `schema_version` | Целое число. Несовместимое изменение payload → новая версия |
| `occurred_at` | ISO 8601 в UTC (`Z`), миллисекунды |
| `aggregate_id`, `aggregate_version` | Агрегат-источник и его версия после изменения. Потребитель не применяет событие с версией ниже уже применённой |
| `correlation_id` | `request_id` исходного HTTP-запроса. Сквозная связь в логах |
| `causation_id` | `event_id` события-причины. Для события из HTTP-запроса совпадает с `correlation_id` |
| UUID / деньги | UUID — строка в нижнем регистре; деньги — `amount_minor` (целое) + `currency` |

## Каталог событий

| Событие | Routing key | Издатель | Агрегат | Payload (основные поля) | Получатель → действие |
|---|---|---|---|---|---|
| `SessionPublished` | `schedule.session.published` | schedule | Session | `session_id, training_type_id, title, trainer_id, trainer_display_name, starts_at, ends_at, capacity` | Booking создаёт `session_inventory` |
| `SessionCancelled` | `schedule.session.cancelled` | schedule | Session | `session_id, cancelled_at, reason` | Booking закрывает запись и отменяет бронирования с возвратом ресурса |
| `PaymentSucceeded` | `payment.payment.succeeded` | payment | Payment | `payment_id, order_id, user_id, amount_minor, currency, completed_at` | Membership активирует абонемент |
| `PaymentDeclined` | `payment.payment.declined` | payment | Payment | `payment_id, order_id, user_id, amount_minor, currency, completed_at, reason` | Membership → заказ `PAYMENT_FAILED` |
| `MembershipActivated` | `membership.membership.activated` | membership | Membership | `membership_id, order_id, user_id, plan_name, starts_at, ends_at, visit_limit` | Notification создаёт уведомление |
| `BookingConfirmed` | `booking.booking.confirmed` | booking | Booking | `booking_id, session_id, user_id, membership_id, session_title, starts_at` | Notification: подтверждение + задача напоминания |
| `BookingCancelled` | `booking.booking.cancelled` | booking | Booking | `booking_id, session_id, user_id, cancelled_at, reason (USER / SESSION_CANCELLED)` | Notification: сообщение об отмене, отмена напоминания |

## Топология RabbitMQ

Объявлена в [`infrastructure/rabbitmq/topology.json`](../infrastructure/rabbitmq/topology.json) и импортируется брокером при старте. Виртуальный хост — `fitclub`.

| Объект | Тип | Назначение |
|---|---|---|
| `fitclub.events` | topic exchange, durable | Все бизнес-события |
| `fitclub.dlx` | direct exchange, durable | Dead-letter для неисправимых сообщений |
| `booking-service.events` | quorum queue | ← `schedule.session.published`, `schedule.session.cancelled` |
| `membership-service.events` | quorum queue | ← `payment.payment.succeeded`, `payment.payment.declined` |
| `notification-service.events` | quorum queue | ← `membership.membership.activated`, `booking.booking.confirmed`, `booking.booking.cancelled` |
| `<queue>.dlq` | quorum queue | Получает сообщения после исчерпания попыток |

Аргументы рабочих очередей: `x-queue-type=quorum`, `x-delivery-limit=5`, `x-dead-letter-exchange=fitclub.dlx`, `x-dead-letter-routing-key=<queue>.dlq`.

Права пользователей брокера (генерируются `GenerateSecrets.java`):

| Пользователь | configure | write | read |
|---|---|---|---|
| `schedule_service`, `payment_service` | — | `fitclub.events` | — |
| `membership_service`, `booking_service` | — | `fitclub.events` | своя очередь |
| `notification_service` | — | — | своя очередь |
| `fitclub_admin` | всё | всё | всё (только Management UI) |

## Гарантии (план реализации)

- **Издатель**: transactional outbox. Бизнес-изменение и строка `outbox_events` фиксируются одной транзакцией PostgreSQL. Фоновый publisher отправляет сообщение с `mandatory=true`, ждёт publisher confirm и проверяет, что сообщение не вернулось как unroutable. Только после этого строка помечается отправленной. Сбой между отправкой и отметкой даёт **повтор** с тем же `event_id`.
- **Потребитель**: обработка и вставка в `processed_events(event_id)` выполняются одной локальной транзакцией. ACK отправляется после commit. Дубликат распознаётся по `event_id`, устаревшее событие — по `aggregate_version`.
- **Повторы**: временные ошибки получают ограниченное число повторов с паузой, затем reject без requeue → DLQ. Страховка от бесконечного requeue — `x-delivery-limit` на стороне брокера.
- **Итог**: доставка at-least-once с идемпотентной обработкой при сохранности хранилищ. Exactly-once **не** обещается.
