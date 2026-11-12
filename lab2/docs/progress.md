# Прогресс

Этот файл позволяет продолжить работу в новом сеансе: здесь записаны текущее состояние, план коммитов и результаты последних проверок.

## План (один шаг = один коммит)

| # | Шаг | Содержание | Статус |
|---|---|---|---|
| 1 | Каркас и инфраструктура | Структура `lab2`, родительский POM, Maven Wrapper, генератор секретов, bootstrap шести БД и ролей, топология RabbitMQ, Compose с PostgreSQL и RabbitMQ, контракты REST/событий, решения по версиям | ✅ готов и проверен |
| 2 | Общий модуль и каркасы шести сервисов | `fitclub-common` (JSON-логи, `X-Request-ID`/MDC, problem+json, обработчики ошибок Security, проверка `X-Service-Token`, `/health` и `/ready`), шесть приложений с `main`, типизированной конфигурацией, Flyway V1, OpenAPI, Dockerfile; ArchUnit-правила слоёв; сервисы в Compose | ✅ готов и проверен |
| 3 | auth-service | Регистрация, вход, RS256 JWT, refresh с ротацией и отзывом, роли, admin API, внутренний trainer-status; тесты | ✅ готов и проверен |
| 4 | API Gateway | Маршруты, проверка JWT, удаление служебных заголовков, закрытие `/internal/**`, request id, обработка недоступного backend; тесты | ✅ готов и проверен |
| 5 | schedule-service | Типы, занятия DRAFT→PUBLISHED→CANCELLED, проверка тренера через Auth, outbox `SessionPublished`/`SessionCancelled`, outbox publisher; тесты | ✅ готов и проверен |
| 6 | membership-service | Тарифы, заказы со снимком, внутренние payment-details и eligibility, Idempotency-Key; тесты | ✅ готов и проверен |
| 7 | payment-service и первый сквозной сценарий | `PaymentProvider`/`SimulatorPaymentProvider`, идемпотентность, outbox; потребитель в Membership (активация, `MembershipActivated`); retry/DLQ; тесты с RabbitMQ | ✅ готов и проверен |
| 8 | booking-service | Проекция занятий, eligibility, блокировки, учёт лимита, отмена и граница окна, обработка `SessionCancelled`, `Idempotency-Key`; конкурентные тесты | ✅ готов и проверен |
| 9 | notification-service | Уведомления, прочтение, сохраняемые напоминания, `NotificationChannel`, tombstone, `Clock`; тесты повторов и восстановления | ✅ готов и проверен |
| 10 | UI и финальный Compose | Статический UI на Nginx (все роли), dev-override, demo-профиль и seeding | ✅ готов и проверен |
| 11 | Сквозные проверки и документация для защиты | E2E через Gateway, сценарии сбоев, `solid.md`, `security.md`, `flows.md`, `testing.md`, `requirements-matrix.md`, `defense.md`, финальный README | ✅ готов и проверен |

## Текущее состояние

**Шаг 11 выполнен** (12.11.2026). Все шаги плана завершены.

Что появилось:
- `tests/e2e` — Maven-модуль (профиль `e2e`, в обычный `verify` не входит). Работает как внешний клиент через Gateway: JDK `HttpClient`, Jackson, Awaitility. Сценарии:
  - `FullJourneyIT` — полный путь абонемент → оплата → запись → уведомления → повтор оплаты → отмена;
  - `AccessControlIT` — аутентификация, роли, владение;
  - `ConcurrencyIT` — последнее место, последнее посещение;
  - `FailureScenariosIT` — остановка Notification, Membership и RabbitMQ;
- `tests/e2e/run-e2e.ps1` / `run-e2e.sh` — изолированный Compose-проект `fitclub-e2e` (порты 18000/18080/15432/15673/15674, свои volumes) с профилем `docker,demo`;
- документы: `solid.md`, `testing.md`, `requirements-matrix.md`, `defense.md`; финальный README (сервисы, стек и причины выбора, запуск, учётные записи, сценарий, endpoint'ы, тесты, сбои, неисправности).

Исправлено по результатам проверок:
- общий `Clock` переведён на шаг 1 мкс (`Clock.tick`): `completed_at` в ответе на оплату отличался от того же значения при чтении из БД (наносекунды против микросекунд PostgreSQL);
- healthcheck `frontend` — `127.0.0.1` вместо `localhost` (в alpine `localhost` резолвился в IPv6, Nginx слушает IPv4; контейнер был `unhealthy`);
- `.ps1`-скрипты сохранены в UTF-8 с BOM: Windows PowerShell 5.1 искажал русский текст.

Проверки:
- `mvnw verify` — **BUILD SUCCESS, 270 тестов, 0 падений** (common 28, auth 52, schedule 45, membership 40, booking 32, payment 31, notification 25, gateway 17);
- `tests/e2e/run-e2e.ps1` — **BUILD SUCCESS, 9 тестов, 0 падений**: стенд собран с нуля, все контейнеры `healthy` (`--wait`), после тестов остановлен.

## Что можно улучшить после защиты

- Браузерные автотесты UI (сейчас UI проверен вручную).
- Немедленный отзыв access JWT (сейчас живёт до `exp`, ≤ 15 минут) — при необходимости через короткий срок жизни или список отзыва.
- Метрики и трассировка (сейчас корреляция по `request_id` в JSON-логах).
