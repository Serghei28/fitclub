# Прогресс

Этот файл позволяет продолжить работу в новом сеансе: здесь записаны текущее состояние, план коммитов и результаты последних проверок.

## План (один шаг = один коммит)

| # | Шаг | Содержание | Статус |
|---|---|---|---|
| 1 | Каркас и инфраструктура | Структура `lab2`, родительский POM, Maven Wrapper, генератор секретов, bootstrap шести БД и ролей, топология RabbitMQ, Compose с PostgreSQL и RabbitMQ, контракты REST/событий, решения по версиям | ✅ готов и проверен |
| 2 | Общий модуль и каркасы шести сервисов | `fitclub-common` (JSON-логи, `X-Request-ID`/MDC, problem+json, обработчики ошибок Security, проверка `X-Service-Token`, `/health` и `/ready`), шесть приложений с `main`, типизированной конфигурацией, Flyway V1, OpenAPI, Dockerfile; ArchUnit-правила слоёв; сервисы в Compose | ✅ готов и проверен |
| 3 | auth-service | Регистрация, вход, RS256 JWT, refresh с ротацией и отзывом, роли, admin API, внутренний trainer-status; тесты | ✅ готов и проверен |
| 4 | API Gateway | Маршруты, проверка JWT, удаление служебных заголовков, закрытие `/internal/**`, request id, обработка недоступного backend; тесты | ⏳ |
| 5 | schedule-service | Типы, занятия DRAFT→PUBLISHED→CANCELLED, проверка тренера через Auth, outbox `SessionPublished`/`SessionCancelled`, outbox publisher; тесты | ✅ готов и проверен |
| 6 | membership-service | Тарифы, заказы со снимком, внутренние payment-details и eligibility, Idempotency-Key; тесты | ✅ готов и проверен |
| 7 | payment-service и первый сквозной сценарий | `PaymentProvider`/`SimulatorPaymentProvider`, идемпотентность, outbox; потребитель в Membership (активация, `MembershipActivated`); retry/DLQ; тесты с RabbitMQ | ✅ готов и проверен |
| 8 | booking-service | Проекция занятий, eligibility, блокировки, учёт лимита, отмена и граница окна, обработка `SessionCancelled`, `Idempotency-Key`; конкурентные тесты | ⏳ |
| 9 | notification-service | Уведомления, прочтение, сохраняемые напоминания, `NotificationChannel`, tombstone, `Clock`; тесты повторов и восстановления | ⏳ |
| 10 | UI и финальный Compose | Статический UI на Nginx (все роли), dev-override, demo-профиль и seeding | ⏳ |
| 11 | Сквозные проверки и документация для защиты | E2E через Gateway, сценарии сбоев, `solid.md`, `security.md`, `flows.md`, `testing.md`, `requirements-matrix.md`, `defense.md`, финальный README | ⏳ |

## Текущее состояние

**Шаг 7 выполнен** (30.10.2026). Первый сквозной сценарий Payment → RabbitMQ → Membership работает.

Что появилось:
- `fitclub-messaging`: `EventProcessor` (processed_events в транзакции обработчика, ACK после commit, 3 попытки → DLQ, неисправимые → DLQ сразу, MDC с `event_id`/`correlation_id`), `EventHandler`, `NonRetryableEventException`, настройки контейнера потребителя;
- payment-service: домен `Payment` (PENDING → SUCCEEDED/DECLINED, конечные статусы не меняются), порт `PaymentProvider` + `SimulatorPaymentProvider`, `CreatePaymentUseCase` (реквизиты из Membership вне транзакции, один платёж на заказ, `Idempotency-Key`), `CompletePaymentUseCase` (статус + outbox `PaymentSucceeded`/`PaymentDeclined`), API `/api/payments`, demo `/api/payments/{id}/simulate` (только local/demo/test);
- membership-service: `PaymentResultHandler` (сверка владельца, суммы, валюты; заказ → PAID; ровно один абонемент от момента оплаты; `MembershipActivated` в outbox; отказ → PAYMENT_FAILED), AMQP-адаптер `PaymentEventsListener`;
- Compose: пароли RabbitMQ для payment и membership, токен payment → membership; `.env.example`: `FITCLUB_PROFILES=docker,demo` для демонстрации;
- [docs/flows.md](flows.md) — сценарий оплаты, транзакции и поведение при сбоях.

Проверки:
- `mvnw verify` по всем модулям — **BUILD SUCCESS**, 231 тест. Новые:
  - payment: 2 доменных, 6 контрактных тестов `PaymentProvider` (симулятор и вторая реализация), 6 тестов правил на fake-адаптерах, 8 интеграционных (сумма с сервера, `X-Service-Token`, повтор и конфликт ключа, **6 параллельных запросов с разными ключами → 1 платёж**, чужой заказ → 404, Membership медленный → 504 без платежа, одно событие при повторах, отказ, чужой `simulate` → 404);
  - membership: 5 интеграционных тестов потребителя на RabbitMQ (абонемент от момента оплаты; повтор `event_id` и новое событие о том же платеже не создают второй абонемент и не продлевают срок; `MembershipActivated` с правильными `correlation_id`/`causation_id` в очереди notification; несовпадение суммы → DLQ без изменений; испорченное сообщение → DLQ без блокировки очереди; отказ → PAYMENT_FAILED и новый заказ разрешён; отказ после успеха ничего не отменяет);
- Docker (`FITCLUB_PROFILES=docker,demo`, настоящие права пользователей RabbitMQ): Мария оплатила ожидающий заказ через Gateway → `SUCCEEDED`; повтор `simulate` → `Idempotent-Replayed`; абонемент «8 посещений в месяц» `ACTIVE` на 30 дней; заказ `PAID`; новый заказ → `ACTIVE_MEMBERSHIP_EXISTS`; цепочка в логах по одному `correlation_id`: gateway → payment (`PaymentSucceeded` опубликован) → membership (активирован, `MembershipActivated` опубликован); `MembershipActivated` и `SessionPublished` ждут в очередях notification и booking.

## Ближайшая задача

Шаг 8: booking-service — проекция занятий из `SessionPublished`/`SessionCancelled` (с `aggregate_version`), eligibility через Membership до транзакции, атомарная запись с блокировками (места и посещения), отмена с границей окна, возврат ресурса один раз, `Idempotency-Key`; конкурентные тесты.
