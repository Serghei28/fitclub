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
| 8 | booking-service | Проекция занятий, eligibility, блокировки, учёт лимита, отмена и граница окна, обработка `SessionCancelled`, `Idempotency-Key`; конкурентные тесты | ✅ готов и проверен |
| 9 | notification-service | Уведомления, прочтение, сохраняемые напоминания, `NotificationChannel`, tombstone, `Clock`; тесты повторов и восстановления | ✅ готов и проверен |
| 10 | UI и финальный Compose | Статический UI на Nginx (все роли), dev-override, demo-профиль и seeding | ⏳ |
| 11 | Сквозные проверки и документация для защиты | E2E через Gateway, сценарии сбоев, `solid.md`, `security.md`, `flows.md`, `testing.md`, `requirements-matrix.md`, `defense.md`, финальный README | ⏳ |

## Текущее состояние

**Шаг 9 выполнен** (30.10.2026). Все шесть бизнес-сервисов реализованы.

Что появилось:
- notification-service: домен `Notification` (dedup_key, прочтение), `BookingState` (версия, «надгробие»), `ReminderJob` (правило планирования, SEND/SKIP), `NotificationTexts` (Europe/Chisinau); `NotificationEventHandler`, `ReminderProcessor`, `NotificationQueryService`; порт `NotificationChannel` + `InAppNotificationChannel`; слушатель `notification-service.events`; фоновый планировщик напоминаний; API `/api/notifications`, `/unread-count`, `/{id}/read`;
- миграция `V2__reminder_jobs_drop_lease.sql`; `fitclub-messaging`: outbox отключаемый (`fitclub.outbox.enabled`).

Проверки:
- `mvnw verify` по всем модулям — **BUILD SUCCESS**, 270 тестов. notification-service: 6 доменных (правило напоминания, пропуск начавшегося, версии, часовой пояс), 5 сценариев на fake-канале, 4 ArchUnit, 5 интеграционных на RabbitMQ (уведомление в API и подавление дублей при повторе того же и нового `event_id`; прочитать может только получатель; напоминание ровно один раз в момент `start − 60 мин` при управляемом `Clock`, отмена подавляет напоминание; задача из БД, созданная «до перезапуска», обрабатывается; опоздавшее подтверждение после отмены ничего не создаёт), 5 каркасных;
- Docker (`docker,demo`): при первом запуске notification обработал три события, ожидавших с шагов 7–8 (`MembershipActivated`, `BookingConfirmed`, `BookingCancelled`), время показано по Кишинёву, напоминание отменённой брони — `CANCELLED`; **notification остановлен → Мария записалась (успешно), `GET /api/notifications` → 503, событие в очереди → notification запущен → уведомление появилось**; задача напоминания `PENDING` сохранилась после `docker compose restart notification-service`; отметка прочтения уменьшила `unread` 4 → 3.

## Ближайшая задача

Шаг 10: UI (HTML/CSS/JS на Nginx, все роли, работа только через Gateway, токены в памяти вкладки, ограниченный polling активации), финальный Compose (frontend, публичный порт), demo-профиль и повторяемое заполнение demo-данными через API.
