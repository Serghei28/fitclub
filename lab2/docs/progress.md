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
| 11 | Сквозные проверки и документация для защиты | E2E через Gateway, сценарии сбоев, `solid.md`, `security.md`, `flows.md`, `testing.md`, `requirements-matrix.md`, `defense.md`, финальный README | ⏳ |

## Текущее состояние

**Шаг 10 выполнен** (04.11.2026). Есть интерфейс для всех ролей и повторяемое demo-наполнение.

Что появилось:
- `frontend/`: Nginx 1.30.6 раздаёт статику и проксирует `/api`, Swagger UI и `/docs/*` в Gateway с того же origin. Заголовки CSP (`script-src 'self'`), `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`; `/internal/` → 404; недоступный Gateway → `503` problem+json;
- UI на ES-модулях без сборки и фреймворков (`api.js`, `time.js`, `dom.js`, `app.js`). Экраны клиента (расписание с местами, тарифы и тестовая оплата, абонемент с остатком посещений, записи с отменой, уведомления), тренера (занятия и участники) и администратора (типы и занятия, тарифы, пользователи и роли);
- токены только в памяти вкладки, общий refresh, `Idempotency-Key` на намерение, блокировка кнопок, понятные тексты ошибок по `code`, время по Кишинёву;
- `infrastructure/demo/seed.py` и сервис `demo-seed` (Compose-профиль `demo`, запуск только явный); секрет `demo_users_password` в генераторе;
- сервис `frontend` в Compose (`127.0.0.1:${UI_HOST_PORT:-8080}`), `frontend/` убран из `.dockerignore`.

Проверки (Docker, `docker,demo`, встроенный браузер):
- `demo-seed`: создал 5 пользователей, 3 тарифа, 2 недостающих типа, 5 занятий, 2 абонемента через тестовую оплату и запись. **Повторный запуск ничего не продублировал**;
- новый клиент через UI: регистрация → вход → тариф → тестовая оплата → «Активация абонемента…» → активирован; запись на занятие на одно место → «мест нет / вы записаны»; отмена → «отменена вами», посещение вернулось (4 из 4); три уведомления, отметка прочтения уменьшила счётчик 3 → 2; неверный пароль → «Неверный email или пароль.»;
- `localStorage`, `sessionStorage`, `document.cookie` пусты; ошибок в консоли нет;
- Gateway остановлен → `/` отдаёт 200, `/api/plans` → `503 DEPENDENCY_UNAVAILABLE`; `/internal/x` → 404; `/swagger-ui.html` и `/docs/booking/v3/api-docs` через Nginx — 200;
- найден и исправлен дефект: `display: flex` у `.row`/`.nav` перекрывал атрибут `hidden` (правило `[hidden] { display: none !important }`).
- экраны тренера и администратора используют те же endpoint'ы, что проверены на шагах 3–9, но вручную в браузере на этом шаге не пройдены.

## Ближайшая задача

Шаг 11: E2E через Gateway, сценарии сбоев (остановка Membership, RabbitMQ, Notification), `solid.md`, `testing.md`, `requirements-matrix.md`, `defense.md`, финальный README.
