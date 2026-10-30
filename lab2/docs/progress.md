# Прогресс

Этот файл позволяет продолжить работу в новом сеансе: здесь записаны текущее состояние, план коммитов и результаты последних проверок.

## План (один шаг = один коммит)

| # | Шаг | Содержание | Статус |
|---|---|---|---|
| 1 | Каркас и инфраструктура | Структура `lab2`, родительский POM, Maven Wrapper, генератор секретов, bootstrap шести БД и ролей, топология RabbitMQ, Compose с PostgreSQL и RabbitMQ, контракты REST/событий, решения по версиям | ✅ готов и проверен |
| 2 | Общий модуль и каркасы шести сервисов | `fitclub-common` (JSON-логи, `X-Request-ID`/MDC, problem+json, обработчики ошибок Security, проверка `X-Service-Token`, `/health` и `/ready`), шесть приложений с `main`, типизированной конфигурацией, Flyway V1, OpenAPI, Dockerfile; ArchUnit-правила слоёв; сервисы в Compose | ✅ готов и проверен |
| 3 | auth-service | Регистрация, вход, RS256 JWT, refresh с ротацией и отзывом, роли, admin API, внутренний trainer-status; тесты | ⏳ |
| 4 | API Gateway | Маршруты, проверка JWT, удаление служебных заголовков, закрытие `/internal/**`, request id, обработка недоступного backend; тесты | ⏳ |
| 5 | schedule-service | Типы, занятия DRAFT→PUBLISHED→CANCELLED, проверка тренера через Auth, outbox `SessionPublished`/`SessionCancelled`, outbox publisher; тесты | ⏳ |
| 6 | membership-service | Тарифы, заказы со снимком, внутренние payment-details и eligibility, Idempotency-Key; тесты | ⏳ |
| 7 | payment-service и первый сквозной сценарий | `PaymentProvider`/`SimulatorPaymentProvider`, идемпотентность, outbox; потребитель в Membership (активация, `MembershipActivated`); retry/DLQ; тесты с RabbitMQ | ⏳ |
| 8 | booking-service | Проекция занятий, eligibility, блокировки, учёт лимита, отмена и граница окна, обработка `SessionCancelled`, `Idempotency-Key`; конкурентные тесты | ⏳ |
| 9 | notification-service | Уведомления, прочтение, сохраняемые напоминания, `NotificationChannel`, tombstone, `Clock`; тесты повторов и восстановления | ⏳ |
| 10 | UI и финальный Compose | Статический UI на Nginx (все роли), dev-override, demo-профиль и seeding | ⏳ |
| 11 | Сквозные проверки и документация для защиты | E2E через Gateway, сценарии сбоев, `solid.md`, `security.md`, `flows.md`, `testing.md`, `requirements-matrix.md`, `defense.md`, финальный README | ⏳ |

## Текущее состояние

**Шаг 2 выполнен** (30.10.2026, Windows 11, Temurin 21.0.13, Docker Desktop 4.97).

Что появилось:
- `libs/fitclub-common`: JSON-логи (Spring Boot structured logging, формат logstash с полями `timestamp`, `service`, `level`, `request_id`, `message`), `RequestIdFilter`, `GlobalExceptionHandler` и `ApiProblem` (problem+json), entry point и access denied handler Spring Security в том же формате, проверка JWT RS256 (`iss`, `aud`, `exp`, `sub`, только RS256), отображение `roles` → authorities, `ServiceTokenFilter` для `/internal/**`, индикатор `migrations`, общая схема OpenAPI;
- `libs/fitclub-test-support`: PostgreSQL 17.11 в Testcontainers, выпуск тестовых JWT, общие ArchUnit-правила;
- шесть сервисов: `main`, `application.yml` и профили `local`/`docker`/`demo`/`test`, Flyway V1 со схемами всех таблиц из задания, Dockerfile (multi-stage, non-root), `ArchitectureTest`, `*ServiceSkeletonIT`;
- Compose: шесть сервисов с healthcheck по `/ready` и отдельными секретами, `docker-compose.dev.yml` с портами 8081–8086, `.vscode/launch.json`.

Проверки:
- `mvnw verify` — **BUILD SUCCESS**:
  - `fitclub-common`: 27 тестов (JWT: истёкший, чужой ключ, неверные iss/aud, HS256, неизвестная роль; формат ошибок 400/401/403/404/409/500/503 без внутренних подробностей; request id; служебные токены и разрешённые пути);
  - каждый сервис: 4 ArchUnit + 5 интеграционных на настоящем PostgreSQL;
  - `auth-service`: `DatabaseOutageIT` — после остановки БД `/ready` = 503, `/health` = 200;
- `docker compose build` и `up` с dev-override: все шесть сервисов `healthy`, `/health`, `/ready`, `/v3/api-docs`, `/swagger-ui.html` отвечают 200;
- ответ без токена — `401 problem+json` с переданным `X-Request-ID`; неверный `X-Service-Token` — 401; токен booking на маршруте payment — 403;
- `flyway_schema_history.installed_by` = `<service>_user`: миграции применены собственной ролью;
- локальный режим: `java -jar services/notification-service/target/notification-service.jar` из `lab2` (профиль `local`, секреты из `secrets/`) стартует и `/ready` = UP.

Шаг 1 (инфраструктура) проверен ранее: изоляция БД 42/42, топология RabbitMQ, сохранность volumes.

## Ближайшая задача

Шаг 3: auth-service — регистрация, вход, RS256 JWT, refresh-токены с ротацией и отзывом, роли, admin API, внутренний `trainer-status`.
