# Прогресс

Этот файл позволяет продолжить работу в новом сеансе: здесь записаны текущее состояние, план коммитов и результаты последних проверок.

## План (один шаг = один коммит)

| # | Шаг | Содержание | Статус |
|---|---|---|---|
| 1 | Каркас и инфраструктура | Структура `lab2`, родительский POM, Maven Wrapper, генератор секретов, bootstrap шести БД и ролей, топология RabbitMQ, Compose с PostgreSQL и RabbitMQ, контракты REST/событий, решения по версиям | ✅ готов и проверен |
| 2 | Общий модуль и каркасы шести сервисов | `fitclub-common` (JSON-логи, `X-Request-ID`/MDC, problem+json, обработчики ошибок Security, проверка `X-Service-Token`, `/health` и `/ready`), шесть приложений с `main`, типизированной конфигурацией, Flyway V1, OpenAPI, Dockerfile; ArchUnit-правила слоёв; сервисы в Compose | ⏳ |
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

**Шаг 1 выполнен.**

Проверено в среде разработки:
- версии стека по Maven Central, BOM и `start.spring.io` (подробно в [decisions.md](decisions.md));
- контрольные суммы Maven Wrapper и дистрибутива Maven 3.9.16;
- синтаксис shell-скриптов (`sh -n`), корректность `topology.json` и `pom.xml`.

Проверено на Windows 11 с Temurin 21.0.13 (30.10.2026):
- `java infrastructure/secrets/GenerateSecrets.java secrets` создаёт 19 файлов, повторный запуск ничего не перезаписывает (хеш ключа не меняется), `rabbitmq_users.json` — корректный JSON с ожидаемыми правами;
- `mvnw.cmd -q validate` проходит: Maven 3.9.16 скачан wrapper'ом, правила enforcer выполнены.

Проверено в Docker Desktop 4.97 (WSL 2), 30.10.2026:
- `docker compose up -d`: `postgres` и `rabbitmq` — healthy, `postgres-bootstrap` — `Exited (0)`, созданы шесть БД с владельцами `<service>_user`;
- `verify-isolation.sh`: 42/42 `OK`, `isolation check passed` (каждая роль открывает только свою БД, `postgres` закрыта);
- RabbitMQ (vhost `fitclub`): 6 quorum-очередей с DLX и `x-delivery-limit=5`, 10 bindings, 6 пользователей, `guest` отсутствует;
- `docker compose down` → `up -d`: volumes сохраняются, bootstrap повторно завершается успешно (идемпотентность).

Шаг 1 полностью проверен.

## Ближайшая задача

Шаг 2: модуль `fitclub-common` и каркасы шести сервисов.
