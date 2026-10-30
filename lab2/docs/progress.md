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
| 6 | membership-service | Тарифы, заказы со снимком, внутренние payment-details и eligibility, Idempotency-Key; тесты | ⏳ |
| 7 | payment-service и первый сквозной сценарий | `PaymentProvider`/`SimulatorPaymentProvider`, идемпотентность, outbox; потребитель в Membership (активация, `MembershipActivated`); retry/DLQ; тесты с RabbitMQ | ⏳ |
| 8 | booking-service | Проекция занятий, eligibility, блокировки, учёт лимита, отмена и граница окна, обработка `SessionCancelled`, `Idempotency-Key`; конкурентные тесты | ⏳ |
| 9 | notification-service | Уведомления, прочтение, сохраняемые напоминания, `NotificationChannel`, tombstone, `Clock`; тесты повторов и восстановления | ⏳ |
| 10 | UI и финальный Compose | Статический UI на Nginx (все роли), dev-override, demo-профиль и seeding | ⏳ |
| 11 | Сквозные проверки и документация для защиты | E2E через Gateway, сценарии сбоев, `solid.md`, `security.md`, `flows.md`, `testing.md`, `requirements-matrix.md`, `defense.md`, финальный README | ⏳ |

## Текущее состояние

**Шаг 5 выполнен** (30.10.2026). Работают schedule-service и издательская сторона событий.

Что появилось:
- `libs/fitclub-messaging`: `EventEnvelope`, `OutboxWriter`, `OutboxRelay` (publisher confirms + returns, backoff), `OutboxHealthIndicator`, настройки RabbitMQ и группа health `messaging`;
- `fitclub-common`: `InternalServiceClient` (таймауты, `X-Service-Token`, `X-Request-ID`, повтор GET, 502/503/504), фабрики `ApiException.dependency*`;
- schedule-service: домен `Session` (DRAFT → PUBLISHED → CANCELLED, снимок зафиксирован после публикации, version), `TrainingType`; `ScheduleCommandService` (сетевой вызов вне транзакции, событие в той же транзакции), `ScheduleQueryService` (черновики только ADMIN); порты `SessionRepository`, `TrainingTypeRepository`, `TrainerDirectory`, `SessionEventPublisher`, `TransactionRunner`; адаптеры JPA, `AuthTrainerDirectory`, `OutboxSessionEventPublisher`; API `/api/training-types`, `/api/sessions` (+ `/mine`, `/publish`, `/cancel`);
- `fitclub-test-support`: `RabbitTestcontainers` (RabbitMQ 4.1.8 с топологией из `infrastructure/rabbitmq/topology.json`, остановка и запуск брокера);
- в Compose schedule-service получает свой пароль RabbitMQ и токен schedule → auth.

Проверки:
- `mvnw verify` по всем модулям — **BUILD SUCCESS**, 178 тестов. schedule-service: 7 доменных тестов, 8 тестов сценариев на fake-адаптерах, 8 тестов HTTP-клиента против WireMock (контракт и заголовки, 404 → пусто, таймаут за < 1,5 с, повтор 5xx, без повтора 401, битое тело → 502, обрыв соединения, закрытый порт → 503), 4 ArchUnit, 13 интеграционных на PostgreSQL + RabbitMQ + WireMock-Auth, 5 каркасных;
- Docker: семь приложений `healthy`; через Gateway созданы тип «Пилатес» и черновик с тренером «Ольга Тренер» (имя получено из Auth), публикация → `version 1`, изменение после публикации → `409 SESSION_NOT_EDITABLE`; строка outbox отправлена (`published_at`), сообщение лежит в `booking-service.events` (1 сообщение, ждёт booking-service); один `request_id` в логах Gateway, schedule и auth и как `correlation_id` события.

## Ближайшая задача

Шаг 6: membership-service — тарифы, заказы со снимком тарифа, один ожидающий заказ и один действующий абонемент на пользователя, `Idempotency-Key` для заказа, внутренние `payment-details` и `eligibility`.
