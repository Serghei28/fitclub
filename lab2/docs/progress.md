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
| 7 | payment-service и первый сквозной сценарий | `PaymentProvider`/`SimulatorPaymentProvider`, идемпотентность, outbox; потребитель в Membership (активация, `MembershipActivated`); retry/DLQ; тесты с RabbitMQ | ⏳ |
| 8 | booking-service | Проекция занятий, eligibility, блокировки, учёт лимита, отмена и граница окна, обработка `SessionCancelled`, `Idempotency-Key`; конкурентные тесты | ⏳ |
| 9 | notification-service | Уведомления, прочтение, сохраняемые напоминания, `NotificationChannel`, tombstone, `Clock`; тесты повторов и восстановления | ⏳ |
| 10 | UI и финальный Compose | Статический UI на Nginx (все роли), dev-override, demo-профиль и seeding | ⏳ |
| 11 | Сквозные проверки и документация для защиты | E2E через Gateway, сценарии сбоев, `solid.md`, `security.md`, `flows.md`, `testing.md`, `requirements-matrix.md`, `defense.md`, финальный README | ⏳ |

## Текущее состояние

**Шаг 6 выполнен** (30.10.2026). Работает membership-service (без обработки событий оплаты — шаг 7).

Что появилось:
- `fitclub-common`: `IdempotencyStore` (advisory-блокировка, хеш канонического тела, сохранённый ответ, `409 IDEMPOTENCY_CONFLICT`);
- membership-service: домен `Plan`, `Money`, `PlanSnapshot`, `MembershipOrder`, `Membership` (интервал `[starts_at, ends_at)`); `PlaceOrderUseCase` (снимок тарифа, один ожидающий заказ и один не закончившийся абонемент, повтор для того же тарифа возвращает заказ), `PlanAdminService`, `MembershipQueryService`; узкие порты `PlanRepository`, `OrderRepository`, `MembershipRepository`; API `/api/plans`, `/api/membership-orders`, `/api/memberships/me`; внутренние `/internal/membership-orders/{id}/payment-details` (payment) и `/internal/memberships/eligibility` (booking).

Проверки:
- `mvnw verify` по всем модулям — **BUILD SUCCESS**, 204 теста. membership-service: 5 доменных (снимок не меняется при изменении тарифа, граница `[start, end)`), 6 тестов правил заказа на fake-адаптерах, 4 ArchUnit, 15 интеграционных на PostgreSQL:
  - цена клиента игнорируется; повтор ключа → тот же заказ и `Idempotent-Replayed`; тот же ключ с другим телом → 409; без ключа → 400;
  - **8 параллельных заказов одного пользователя с разными ключами → ровно 1 заказ** (один `201`, остальные `200` с тем же id); **6 параллельных повторов одного ключа → действие выполнено 1 раз**;
  - ожидающий заказ другого тарифа → `PENDING_ORDER_EXISTS`, не закончившийся абонемент → `ACTIVE_MEMBERSHIP_EXISTS`, снятый тариф → `PLAN_NOT_AVAILABLE`, чужой заказ → 404;
  - payment-details только с токеном payment (booking → 403, JWT → 401), eligibility на границах периода;
- Docker: через Gateway созданы тарифы «8 посещений в месяц» (45000) и «Безлимит на месяц» (`visit_limit: null`); клиент «Мария» сделала заказ (цена из тарифа), повтор ключа → `Idempotent-Replayed: true`, другой тариф → `PENDING_ORDER_EXISTS`; payment-details отвечает с токеном payment на dev-порту и закрыт снаружи через Gateway (404).

## Ближайшая задача

Шаг 7: payment-service и первый сквозной сценарий — `PaymentProvider`/`SimulatorPaymentProvider`, создание платежа по заказу (реквизиты из Membership), `Idempotency-Key`, demo-завершение `SUCCESS`/`DECLINE`, outbox `PaymentSucceeded`/`PaymentDeclined`; потребитель событий в Membership (идемпотентная активация, `MembershipActivated`), retry и DLQ.
