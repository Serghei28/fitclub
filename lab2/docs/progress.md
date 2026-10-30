# Прогресс

Этот файл позволяет продолжить работу в новом сеансе: здесь записаны текущее состояние, план коммитов и результаты последних проверок.

## План (один шаг = один коммит)

| # | Шаг | Содержание | Статус |
|---|---|---|---|
| 1 | Каркас и инфраструктура | Структура `lab2`, родительский POM, Maven Wrapper, генератор секретов, bootstrap шести БД и ролей, топология RabbitMQ, Compose с PostgreSQL и RabbitMQ, контракты REST/событий, решения по версиям | ✅ готов и проверен |
| 2 | Общий модуль и каркасы шести сервисов | `fitclub-common` (JSON-логи, `X-Request-ID`/MDC, problem+json, обработчики ошибок Security, проверка `X-Service-Token`, `/health` и `/ready`), шесть приложений с `main`, типизированной конфигурацией, Flyway V1, OpenAPI, Dockerfile; ArchUnit-правила слоёв; сервисы в Compose | ✅ готов и проверен |
| 3 | auth-service | Регистрация, вход, RS256 JWT, refresh с ротацией и отзывом, роли, admin API, внутренний trainer-status; тесты | ✅ готов и проверен |
| 4 | API Gateway | Маршруты, проверка JWT, удаление служебных заголовков, закрытие `/internal/**`, request id, обработка недоступного backend; тесты | ⏳ |
| 5 | schedule-service | Типы, занятия DRAFT→PUBLISHED→CANCELLED, проверка тренера через Auth, outbox `SessionPublished`/`SessionCancelled`, outbox publisher; тесты | ⏳ |
| 6 | membership-service | Тарифы, заказы со снимком, внутренние payment-details и eligibility, Idempotency-Key; тесты | ⏳ |
| 7 | payment-service и первый сквозной сценарий | `PaymentProvider`/`SimulatorPaymentProvider`, идемпотентность, outbox; потребитель в Membership (активация, `MembershipActivated`); retry/DLQ; тесты с RabbitMQ | ⏳ |
| 8 | booking-service | Проекция занятий, eligibility, блокировки, учёт лимита, отмена и граница окна, обработка `SessionCancelled`, `Idempotency-Key`; конкурентные тесты | ⏳ |
| 9 | notification-service | Уведомления, прочтение, сохраняемые напоминания, `NotificationChannel`, tombstone, `Clock`; тесты повторов и восстановления | ⏳ |
| 10 | UI и финальный Compose | Статический UI на Nginx (все роли), dev-override, demo-профиль и seeding | ⏳ |
| 11 | Сквозные проверки и документация для защиты | E2E через Gateway, сценарии сбоев, `solid.md`, `security.md`, `flows.md`, `testing.md`, `requirements-matrix.md`, `defense.md`, финальный README | ⏳ |

## Текущее состояние

**Шаг 4 выполнен** (30.10.2026). Работает API Gateway.

Что появилось:
- модуль `gateway` (Spring Cloud Gateway Server WebFlux 5.0.4): `GatewayRoutes` (маршруты с явным порядком, `/internal/**` не публикуется), `GatewaySecurityConfiguration` (реактивная проверка JWT RS256), `RequestIdFilter` (X-Request-ID, JSON-лог с `route`, `duration_ms`), `PathGuardFilter` (`/internal` → 404, `..`/`//`/`;` → 400), `StripInternalHeadersFilter` (удаляет `X-Service-Token`), `GatewayErrorHandler` (503/504/502/404 в problem+json), общий Swagger UI для шести сервисов;
- Dockerfile и сервис `gateway` в Compose (`127.0.0.1:8000`), healthcheck по `/ready`;
- в сервисах `server.forward-headers-strategy=framework`: OpenAPI через Gateway указывает на адрес Gateway.

Проверки:
- `mvnw verify` — **BUILD SUCCESS**, всего 142 теста. Gateway: 15 интеграционных тестов с настоящим Gateway и WireMock-сервисами и 2 ArchUnit:
  - маршрутизация (участники занятия → booking, занятие → schedule), проброс токена, `X-Service-Token` удаляется, ошибки сервиса проходят без изменений, OpenAPI с `X-Forwarded-Host`;
  - 401 без токена и с поддельным или истёкшим токеном (до сервиса не доходит), `/internal/**` → 404, `..`/`%2e%2e`/`;` → 400;
  - `X-Request-ID` передаётся сервису и возвращается, недопустимый заменяется;
  - остановленный сервис → 503 `DEPENDENCY_UNAVAILABLE`, медленный → 504 `DEPENDENCY_TIMEOUT` за ~1 с, `/health` и `/ready` Gateway не зависят от сервисов;
- Docker: семь приложений `healthy`; вход ADMIN, `/api/auth/me` и `/api/admin/users` через Gateway; один `request_id` в логах Gateway и auth-service; `/internal/...` → 404; `--path-as-is /api/auth/../../internal/...` → 400; `docker compose stop notification-service` → `503 DEPENDENCY_UNAVAILABLE`; Swagger UI Gateway 200, `servers` = `http://localhost:8000`.

Найдено и исправлено: Gateway 5 не передаёт `X-Forwarded-*` без `trusted-proxies`, из-за этого OpenAPI через Gateway показывал внутренний адрес `http://auth-service:8080`.

## Ближайшая задача

Шаг 5: schedule-service — типы тренировок, занятия DRAFT → PUBLISHED → CANCELLED, проверка тренера через внутренний API Auth (HTTP-клиент с таймаутами и X-Request-ID), outbox `SessionPublished`/`SessionCancelled` и outbox publisher в RabbitMQ.
