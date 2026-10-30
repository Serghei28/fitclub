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

**Шаг 3 выполнен** (30.10.2026). auth-service реализован полностью.

Что появилось:
- `domain`: `User`, `Email` (нормализация), `PasswordPolicy` (8 символов, ≤ 72 байт UTF-8), `RefreshToken` (ротация, цепочка), `Role`, `AuthErrors` — без Spring/JPA;
- `application`: `RegisterUserUseCase`, `LoginUseCase`, `RefreshSessionUseCase`, `LogoutUseCase`, `ChangeUserRolesUseCase`, `UserQueryService`, `EnsureInitialAdminUseCase`, `SessionIssuer`; порты `UserRepository`, `RefreshTokenRepository`, `PasswordHasher`, `AccessTokenIssuer`;
- `infrastructure`: JPA-адаптеры, `SpringPasswordHasher` (BCrypt), `JwtAccessTokenIssuer` (RS256, проверка соответствия ключей), `AuthProperties`, `InitialAdminBootstrap`;
- `api`: `AuthController`, `AdminUserController`, `InternalUserController` с OpenAPI-описаниями;
- `fitclub-common`: `DomainException`, `PageResponse`, `Clock`, `ProblemErrorController` (problem+json для `/error`);
- секрет `auth_initial_admin_password`; в Compose закрытый ключ JWT монтируется только в auth-service;
- [docs/security.md](security.md).

Проверки:
- `mvnw verify` — **BUILD SUCCESS** по всем модулям:
  - auth-service: 15 unit-тестов use case'ов на fake-адаптерах (без Spring и БД), 4 доменных, 2 теста выпуска JWT (токен принимается общим декодером, несоответствие ключей обнаруживается при старте), 4 ArchUnit, 21 интеграционный на PostgreSQL (`AuthApiIT`), 5 каркасных, 1 потеря БД;
  - в `AuthApiIT`: роли из тела регистрации игнорируются, email без учёта регистра, **6 параллельных регистраций одного email → ровно 1 пользователь**, BCrypt в БД, одинаковая ошибка для неверного пароля и неизвестного email, ротация и отзыв цепочки при повторном использовании, logout (access живёт до exp), refresh хранится только хешем, ADMIN API (403 для клиента, фильтр роли, лимит size, назначение TRAINER попадает в следующий токен, запрет снять ADMIN с себя), внутренний trainer-status (401 без токена, 404 для неизвестного);
  - `fitclub-common`: 28 тестов, включая problem+json для `/error`;
- Docker: первый ADMIN создан при запуске (`Initial admin bootstrap: CREATED`), регистрация с кириллицей, назначение TRAINER, `trainer-status` с токеном schedule → 200, с чужим токеном → 401; путь с `//` → 400 problem+json; токен auth принят booking-service без обращения к Auth; в логах нет паролей и токенов.

## Ближайшая задача

Шаг 4: API Gateway — маршруты всех сервисов, проверка JWT на защищённых маршрутах, удаление служебных заголовков, закрытие `/internal/**`, передача `X-Request-ID`, контролируемый ответ при недоступном backend.
