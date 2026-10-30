# События: контракты v1

> Статус: **все издатели и потребители реализованы** (шаги 5–9). Публикуют schedule, payment, membership и booking. Потребляют membership, booking и notification.

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

## Издатель: как устроен outbox (реализовано)

```mermaid
sequenceDiagram
    participant API as SessionController
    participant UC as ScheduleCommandService
    participant Auth as auth-service
    participant DB as schedule_db
    participant Relay as OutboxRelay
    participant MQ as RabbitMQ
    API->>UC: POST /api/sessions/{id}/publish
    UC->>Auth: GET /internal/users/{id}/trainer-status (вне транзакции, таймауты)
    Auth-->>UC: trainer=true, display_name
    UC->>DB: BEGIN; UPDATE sessions (PUBLISHED, version+1); INSERT outbox_events; COMMIT
    UC-->>API: 200 PUBLISHED
    loop каждую секунду
        Relay->>DB: SELECT … FOR UPDATE SKIP LOCKED (неотправленные)
        Relay->>MQ: publish persistent, mandatory, ждать confirm
        MQ-->>Relay: ack (и нет basic.return)
        Relay->>DB: UPDATE published_at
    end
```

| Компонент | Файл | Что делает |
|---|---|---|
| `OutboxWriter` | `libs/fitclub-messaging/.../OutboxWriter.java` | Пишет событие в `outbox_events` **только внутри открытой транзакции** (иначе `IllegalStateException`). Заполняет `event_id`, `occurred_at`, `producer`, `correlation_id` (request_id запроса или событие-причина), `causation_id` |
| `OutboxRelay` | `.../OutboxRelay.java` | Раз в `fitclub.outbox.poll-interval` (1 с) берёт до 50 событий `FOR UPDATE SKIP LOCKED`, публикует с `mandatory=true`, ждёт publisher confirm (5 с). Отмечает отправленным только при `ack` **и** отсутствии возврата |
| Повтор | `OutboxProperties.backoffAfter` | Неудача → `attempts+1`, `last_error`, `next_attempt_at` = 2 с, 4 с, 8 с … до 5 минут. При недоступном брокере проход прерывается до следующего цикла |
| `OutboxHealthIndicator` | `.../OutboxHealthIndicator.java` | DOWN, если есть события старше 60 с без отправки. Вместе с `rabbit` входит в группу `/actuator/health/messaging`, **не** в `/ready` |
| Издатель schedule | `services/schedule-service/.../OutboxSessionEventPublisher.java` | Адаптер порта `SessionEventPublisher`: `SessionPublished` (полный снимок занятия) и `SessionCancelled` |

Свойства AMQP-сообщения: `delivery_mode=PERSISTENT`, `content_type=application/json`, `message_id=event_id`, `type=event_type`, `correlation_id`. Приложение не объявляет топологию (`spring.rabbitmq.dynamic=false`, у пользователя нет прав `configure`).

Проверено тестами `ScheduleApiIT`:
- публикация занятия → строка outbox в той же транзакции → сообщение в `booking-service.events` с полным конвертом;
- брокер остановлен (`rabbitmqctl stop_app`) → публикация занятия всё равно `200`, `/ready` = 200, `/actuator/health/messaging` = 503, у события растёт `attempts`; после `start_app` событие доставлено;
- routing key без привязки → брокер подтверждает приём, но возвращает сообщение, и событие остаётся неотправленным с `last_error = unroutable…`;
- прошедшее занятие не публикуется, и строка outbox не создаётся.

## Потребитель: идемпотентность, повторы, DLQ (реализовано)

`EventProcessor` (`libs/fitclub-messaging`) обрабатывает каждое входящее сообщение так:

| Шаг | Что происходит |
|---|---|
| 1. Разбор | Конверт читается как `EventEnvelope`. Не JSON или нет `event_id` / `event_type` / `payload` → `AmqpRejectAndDontRequeueException` → **DLQ сразу** |
| 2. Маршрут | Обработчик выбирается по `event_type`. Неизвестный тип → DLQ. Обработчик проверяет `schema_version` (поддерживается 1) |
| 3. Транзакция | `INSERT INTO processed_events … ON CONFLICT DO NOTHING`. Строка уже была → **повторная доставка, пропуск**. Иначе вызывается обработчик в той же транзакции |
| 4. ACK | `acknowledge-mode=auto`: контейнер подтверждает сообщение после возврата из метода, то есть **после commit** |
| 5. Ошибки | `NonRetryableEventException` (противоречивые данные, неверный payload) → DLQ сразу. Прочие исключения (например, БД) → до 3 попыток с паузой 0,5 с и 1 с, затем DLQ |
| 6. Страховка | Брокер ограничивает число повторных доставок (`x-delivery-limit=5` на quorum-очереди), поэтому «горячего» бесконечного requeue нет |

Во время обработки в MDC стоят `event_id`, `event_type`, `correlation_id` и `causation_id`. Событие-следствие, записанное в outbox внутри обработчика, получает тот же `correlation_id` и `causation_id` = `event_id` события-причины (проверено: `MembershipActivated` ссылается на `PaymentSucceeded`).

Настройки контейнера (`fitclub-messaging-defaults.properties`): `default-requeue-rejected=false`, `prefetch=10`, `missing-queues-fatal=false` (сервис стартует, даже если брокер ещё недоступен, и переподключается сам), встроенный retry контейнера выключен.

### Обработка в membership-service

| Событие | Действие | Повтор | Противоречие |
|---|---|---|---|
| `PaymentSucceeded` | Проверка владельца, суммы и валюты по снимку заказа → заказ `PAID` → абонемент `[completed_at, completed_at + duration)` → `MembershipActivated` | Тот же `event_id` — пропуск. Другой `event_id` о том же платеже — `ALREADY_APPLIED`, срок не продлевается | Несовпадение или заказ оплачен другим платежом → DLQ |
| `PaymentDeclined` | Ожидающий заказ → `PAYMENT_FAILED` | Заказ уже не ожидает оплаты → игнорируется | Заказ не найден или чужой → DLQ |

Очерёдность событий одного платежа не важна: `PaymentDeclined` после `PaymentSucceeded` ничего не отменяет.

### Обработка в booking-service

| Событие | Действие | Устаревшее / повтор |
|---|---|---|
| `SessionPublished` | Создать `session_inventory` (снимок, `capacity`, `booked_count = 0`, `OPEN`) | Версия не больше применённой или занятие уже `CANCELLED` → игнорируется |
| `SessionCancelled` | `CANCELLED`, отменить все `CONFIRMED`-брони с возвратом места и посещения, по каждой — `BookingCancelled` | Уже отменено или версия меньше → игнорируется. Пришло раньше публикации → «надгробие» |

`BookingConfirmed` (`aggregate_version = 1`) и `BookingCancelled` (`aggregate_version = 2`) публикуются через outbox вместе с изменением брони. Подробности сценариев — [flows.md](flows.md).

### Обработка в notification-service

| Событие | Действие | Защита от повторов и порядка |
|---|---|---|
| `MembershipActivated` | Уведомление «Абонемент активирован» | `dedup_key = MEMBERSHIP_ACTIVATED:<membership_id>` |
| `BookingConfirmed` | Проекция брони `CONFIRMED`, уведомление, задача напоминания `remind_at = starts_at − REMINDER_LEAD_MINUTES` (или «сразу», если этот момент прошёл; для начавшегося занятия — без задачи) | Версия ≤ известной (в том числе после отмены) → игнорируется. Одна задача на бронь (`uq_reminder_jobs_booking`) |
| `BookingCancelled` | Проекция `CANCELLED` («надгробие»), уведомление об отмене (пользователем или клубом), ожидающее напоминание → `CANCELLED` | Отмена раньше подтверждения: опоздавшее `BookingConfirmed` не создаёт ни уведомления, ни напоминания |

Notification только потребляет события: outbox у него отключён (`fitclub.outbox.enabled=false`), в группе `messaging` только `rabbit`.

## Гарантии

- **Издатель**: transactional outbox. Бизнес-изменение и строка `outbox_events` фиксируются одной транзакцией PostgreSQL. Фоновый publisher отправляет сообщение с `mandatory=true`, ждёт publisher confirm и проверяет, что сообщение не вернулось как unroutable. Только после этого строка помечается отправленной. Сбой между отправкой и отметкой даёт **повтор** с тем же `event_id`.
- **Потребитель**: обработка и вставка в `processed_events(event_id)` выполняются одной локальной транзакцией. ACK отправляется после commit. Дубликат распознаётся по `event_id`, устаревшее событие — по `aggregate_version`.
- **Повторы**: временные ошибки получают ограниченное число повторов с паузой, затем reject без requeue → DLQ. Страховка от бесконечного requeue — `x-delivery-limit` на стороне брокера.
- **Итог**: доставка at-least-once с идемпотентной обработкой при сохранности хранилищ. Exactly-once **не** обещается.
