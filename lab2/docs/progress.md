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
| 9 | notification-service | Уведомления, прочтение, сохраняемые напоминания, `NotificationChannel`, tombstone, `Clock`; тесты повторов и восстановления | ⏳ |
| 10 | UI и финальный Compose | Статический UI на Nginx (все роли), dev-override, demo-профиль и seeding | ⏳ |
| 11 | Сквозные проверки и документация для защиты | E2E через Gateway, сценарии сбоев, `solid.md`, `security.md`, `flows.md`, `testing.md`, `requirements-matrix.md`, `defense.md`, финальный README | ⏳ |

## Текущее состояние

**Шаг 8 выполнен** (30.10.2026). Работает booking-service.

Что появилось:
- домен: `SessionInventory` (проекция с `aggregate_version` и «надгробием»), `Booking`, `MembershipUsage`, `BookingPolicy` (порядок проверок, окно отмены с включённой границей);
- `CreateBookingUseCase` (повтор ключа → eligibility вне транзакции → блокировки занятие → учёт посещений → проверки → запись + outbox), `CancelBookingUseCase`, `SessionProjectionHandler` (`SessionPublished`/`SessionCancelled`, массовая отмена с возвратом), `BookingQueryService`;
- адаптеры: JPA с `lockFresh` (refresh + `PESSIMISTIC_WRITE`), `INSERT … ON CONFLICT` для проекции и учёта, `MembershipEligibilityClient`, слушатель `booking-service.events`, outbox `BookingConfirmed`/`BookingCancelled`;
- API: `/api/bookings`, `/api/bookings/{id}/cancel`, `/api/bookings/me`, `/api/bookings/usage/me`, `/api/availability`, `/api/sessions/{id}/bookings`;
- миграция `V2__booking_user_display_name.sql`; `AuthenticatedUser.displayName`, `IdempotencyStore.lookup`, `TestJwt.name`.

Проверки:
- `mvnw verify` по всем модулям — **BUILD SUCCESS**, 254 теста. booking-service: 5 доменных, 4 ArchUnit, 18 интеграционных на PostgreSQL + RabbitMQ + WireMock-Membership и 5 каркасных:
  - правила: `SESSION_NOT_READY`, eligibility запрашивается на `starts_at`, место + посещение + событие v1, повторная запись, полная группа, исчерпанный лимит, безлимит, начавшееся занятие, Membership медленный → 504, повтор старого ключа после отмены → тот же ответ;
  - отмена: ровно на границе 120 минут можно, через секунду нельзя; повторная отмена не возвращает посещение второй раз; чужая бронь → 404;
  - участники: назначенный тренер и ADMIN — да, другой тренер и клиент — 403;
  - проекция: `SessionCancelled` отменяет все брони с возвратом, по одному `BookingCancelled`; опоздавший `SessionPublished` не открывает запись; отмена раньше публикации → «надгробие»;
  - **конкурентность**: 2 клиента на последнее место → 1; 10 клиентов на 3 места → 3; 1 клиент, 2 занятия, 1 посещение → 1; отмена пользователем ∥ отмена занятия → возврат один раз и одно событие (тест нашёл ошибку кэша Hibernate, исправлено, 3 прогона подряд зелёные);
- Docker (`docker,demo`): при первом запуске booking обработал `SessionPublished`, ожидавшее с шага 5; через Gateway: Иван без абонемента → `MEMBERSHIP_NOT_VALID`; Мария записалась (места 1 → 0, посещения 8 → 7), повтор → `ALREADY_BOOKED`; тренер Ольга видит участницу «Мария Клиентова», клиент → 403; отмена Марии вернула место и посещение; в `notification-service.events` ждут `MembershipActivated`, `BookingConfirmed`, `BookingCancelled`; трассировка по одному `request_id`: gateway → booking → membership (eligibility).

## Ближайшая задача

Шаг 9: notification-service — уведомления по `MembershipActivated`, `BookingConfirmed`, `BookingCancelled`; API списка, количества непрочитанных и отметки прочтения; сохраняемые напоминания (`REMINDER_LEAD_MINUTES`), защита от двойного выполнения, отмена напоминания, проекция/«надгробие» по версии бронирования, `Clock` в тестах; сценарий «Notification остановлен → запущен».
