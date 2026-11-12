# Матрица требований

Каждое требование лабораторной → где реализовано → чем проверяется. Пути — от каталога `lab2`.

## Обязательные требования лабораторной

| # | Требование | Где реализовано | Как проверить |
|---|---|---|---|
| 1 | Шесть независимо запускаемых backend-микросервисов со своей ответственностью и бизнес-логикой | `services/auth-service`, `schedule-service`, `membership-service`, `booking-service`, `payment-service`, `notification-service`: у каждого свой `pom.xml`, `Dockerfile`, `main`, конфигурация и миграции. Границы — [architecture.md](architecture.md) | `docker compose ps` — шесть контейнеров `healthy`; каждый запускается отдельно (`mvnw -pl services/<name> spring-boot:run`, README «Запуск сервиса из VS Code или терминала»); `noDependencyOnOtherServices` в ArchUnit |
| 2 | Отдельный REST API каждого сервиса | `api/*Controller` в каждом сервисе; общий Swagger UI Gateway | http://localhost:8000/swagger-ui.html (шесть документов), [api.md](api.md); `*ApiIT` |
| 3 | Реальное сетевое взаимодействие | REST: Schedule → Auth (trainer-status), Payment → Membership (payment-details), Booking → Membership (eligibility). RabbitMQ: `session.*`, `payment.*`, `membership.activated`, `booking.*` — [events.md](events.md) | `ScheduleApiIT`, `PaymentApiIT`, `BookingIT` (WireMock/Testcontainers); E2E `FullJourneyIT` через настоящие контейнеры |
| 4 | Сохранение данных в БД | Шесть логических БД `<service>_db` в PostgreSQL 17, Flyway-миграции `db/migration/V*__*.sql`, ограничения CHECK/UNIQUE | `docker compose restart` — данные сохраняются (volume `postgres-data`); `infrastructure/postgres/verify-isolation.sh`; `*IT` на Testcontainers |
| 5 | Отдельная конфигурация каждого сервиса | `application.yml` + профили `local`/`docker`/`demo`/`test`; типизированные `@ConfigurationProperties`; свои секреты через `/run/secrets` | `docker-compose.yml` (свои `secrets` и переменные у каждого сервиса); `*SkeletonIT` |
| 6 | Authentication и authorization | auth-service: регистрация, вход, RS256 JWT, refresh с ротацией; проверка JWT в каждом сервисе (`fitclub-common`) и в Gateway; роли и проверки владельца — в бизнес-сервисах; `X-Service-Token` для `/internal/**` — [security.md](security.md) | `AuthApiIT`, `CommonWebSecurityTest`, `GatewayIT`, E2E `AccessControlIT` |
| 7 | Валидация и обработка ошибок: авторизация, отсутствующие ресурсы, сбои БД, внешних сервисов и timeout | Bean Validation + доменные проверки; единый problem+json с `code` и `request_id`; `InternalServiceClient` (таймауты, 502/503/504); `ProblemErrorController`; Gateway → 503/504 | `CommonWebSecurityTest` (валидация, 401/403/404, ошибка БД → 503, скрытие деталей); `PaymentApiIT.slowMembershipGivesTimeoutAndNoPayment`; `GatewayIT.slowServiceGivesControlled504`; E2E `FailureScenariosIT` |
| 8 | Логи с timestamp, service, level, request_id, message | Структурированные JSON-логи Spring Boot (logstash), поле `service`, MDC `request_id` из `X-Request-ID` (`RequestIdFilter`), без токенов и паролей | `docker compose logs booking-service`; `RequestIdFilterTest`, `GatewayIT.validRequestIdIsPassedToServiceAndReturned` |
| 9 | OpenAPI, Swagger UI и основные endpoint'ы в README | springdoc 3.2.0 в каждом сервисе; общий Swagger UI в Gateway (и через Nginx `/swagger-ui.html`); README «Основные endpoint'ы», [api.md](api.md) | `GatewayIT.serviceOpenApiIsAvailableThroughGateway`; `AuthApiIT.internalRouteIsListedInOpenApi…` |
| 10 | Health checks | `/health` (liveness) и `/ready` (БД и миграции) в каждом сервисе и Gateway; `/nginx-health` у UI; healthcheck в Compose | `*SkeletonIT`; `DatabaseOutageIT` (ready падает, health живёт); `GatewayIT.gatewayHealthDoesNotDependOnServices` |
| 11 | Тестирование | 270+ модульных и интеграционных тестов, ArchUnit, E2E — [testing.md](testing.md) | `mvnw verify`; `tests/e2e/run-e2e.ps1` |
| 12 | Обоснование технологий и разделения ответственности | [decisions.md](decisions.md) (версии, D1–D70), [architecture.md](architecture.md), [solid.md](solid.md) | — |

## Дополнительные решения проекта

| Требование | Где реализовано | Как проверить |
|---|---|---|
| API Gateway | `gateway/` (Spring Cloud Gateway WebFlux): маршруты `/api/**`, проверка JWT, удаление служебных заголовков, `/internal/**` закрыт, `X-Request-ID`, 503/504 | `GatewayIT` (15 тестов); E2E `AccessControlIT` |
| Асинхронность через RabbitMQ | `fitclub-messaging`: transactional outbox + relay с publisher confirms; `EventProcessor` с `processed_events`, retry и DLQ; quorum-очереди — [events.md](events.md) | `PaymentEventsIT`, `ScheduleApiIT`, `NotificationIT`; E2E `FailureScenariosIT.brokerDown` |
| Изоляция данных сервисов | Отдельная БД и роль на сервис (`infrastructure/postgres/bootstrap-databases.sh`), чужие БД недоступны, ссылки между сервисами — UUID без внешних ключей | `infrastructure/postgres/verify-isolation.sh`; `noDependencyOnOtherServices` |
| Небольшой UI | `frontend/` (Nginx + HTML/CSS/JS), все роли, только через `/api`, токены в памяти вкладки | Ручная проверка в браузере (progress, шаг 10); README «Проверка шага 10» |
| Воспроизводимый запуск через Docker Compose | `docker-compose.yml` (сборка из исходников, healthchecks, volumes, секреты), `docker-compose.dev.yml`, генератор секретов | README «Первый запуск»; `run-e2e` поднимает стенд с нуля |

## Критерии оценки

| Критерий (вес) | Что показать | Где |
|---|---|---|
| 5–6+ сервисов (20%) | Шесть отдельных приложений и работающий API | `docker compose ps`, Swagger, E2E `FullJourneyIT` |
| Архитектура и ответственность (20%) | Границы, владение данными, независимый запуск, SOLID внутри | [architecture.md](architecture.md), [solid.md](solid.md), ArchUnit |
| Взаимодействие (15%) | Реальные REST-вызовы и события RabbitMQ | [flows.md](flows.md), [events.md](events.md), E2E |
| Persistence (10%) | Шесть логических БД, миграции, ограничения, сохранность после перезапуска | `infrastructure/postgres`, `db/migration`, `NotificationIT.reminderStoredBeforeRestart…` |
| API и документация (10%) | OpenAPI/Swagger каждого сервиса, примеры, README | [api.md](api.md), README |
| Authentication/authorization (5%) | JWT, роли, владелец, защита внутренних API | [security.md](security.md), `AuthApiIT`, `CommonWebSecurityTest`, `AccessControlIT` |
| Logging/error handling (10%) | JSON-логи, корреляция, управляемые ошибки зависимостей | `fitclub-common`, `GatewayIT`, `FailureScenariosIT` |
| Тестирование (10%) | Правила, интеграции, конкурентность, E2E | [testing.md](testing.md) |

## Бизнес-правила

| Правило | Где реализовано | Как проверить |
|---|---|---|
| Регистрация даёт только CLIENT, роли назначает ADMIN | `RegisterUserUseCase`, `ChangeUserRolesUseCase` | `AuthApiIT.publicRegistrationAssignsOnlyClient…`, `adminAssignsTrainerRole…`; E2E `clientCannotEscalate` |
| Email нормализуется и уникален, пароль — BCrypt с учётом лимита 72 байт | `Email`, `PasswordPolicy`, `SpringPasswordHasher`, UNIQUE в `users` | `AuthDomainTest`, `AuthApiIT.emailIsNormalized…`, `passwordRulesAreValidated` |
| Access JWT RS256 15 мин, проверка подписи, alg, iss, aud, exp; refresh — хэш, ротация, отзыв | `JwtAccessTokenIssuer`, `RefreshSessionUseCase`, `fitclub-common` security | `CommonWebSecurityTest`, `AuthApiIT.refreshRotatesAndReuseRevokesFamily` |
| Занятие DRAFT → PUBLISHED → CANCELLED, тренер проверяется через Auth, после публикации снимок не меняется | `Session`, `ScheduleCommandService`, `AuthTrainerDirectory` | `SessionTest`, `ScheduleApiIT` |
| Один ожидающий заказ и один не закончившийся абонемент; цена и лимит — снимок тарифа | `PlaceOrderUseCase`, `MembershipOrder`, `PlanSnapshot` | `PlaceOrderRulesTest`, `MembershipApiIT` |
| Оплата — симулятор, сумма из заказа, один платёж на заказ, `Idempotency-Key` | `CreatePaymentUseCase`, `CompletePaymentUseCase`, `SimulatorPaymentProvider`, `IdempotencyStore` | `PaymentRulesTest`, `PaymentApiIT`, `FullJourneyIT` |
| Активация только подтверждённого заказа с совпадающей суммой; повтор не продлевает | `PaymentResultHandler` | `PaymentEventsIT`, `FullJourneyIT` |
| Запись: действующий абонемент на момент начала, места, лимит, без дублей; отмена до окна, однократный возврат | `CreateBookingUseCase`, `CancelBookingUseCase`, `BookingPolicy`, блокировки занятие → учёт → бронь | `BookingIT`, `BookingDomainTest`, E2E `ConcurrencyIT` |
| Уведомления без дублей; напоминания в БД, переживают перезапуск, отмена подавляет | `NotificationEventHandler`, `ReminderProcessor`, `reminder_jobs` | `NotificationIT`, `NotificationScenariosTest`, E2E `notificationDown` |

## Безопасность конфигурации и demo-данные

| Требование | Где | Как проверить |
|---|---|---|
| Секреты, ключи и `.env` вне Git; генератор не печатает значения | `.gitignore` (`/secrets/`, `.env`), `infrastructure/secrets/GenerateSecrets.java` | `git status` не показывает `secrets/`; вывод генератора — только имена файлов |
| Отдельный service credential на каждую пару сервисов | `service_token_<caller>_to_<callee>`, `ServiceTokenFilter` | `CommonWebSecurityTest.callerReachesOnlyItsOwnRoutes` |
| Токены не в логах, URL и хранилище браузера | `api.js` (память вкладки), логирование без заголовков | Проверка шага 10: `localStorage`/`sessionStorage`/cookie пусты |
| Demo: 1 ADMIN, ≥2 TRAINER, ≥3 CLIENT, 3 тарифа, будущие занятия с группой на одно место; только явным действием, повторяемо, через API | `infrastructure/demo/seed.py`, сервис `demo-seed` (профиль `demo`) | `docker compose --profile demo run --rm demo-seed` дважды — второй раз без новых данных |
| Версии без latest/SNAPSHOT/RC | `pom.xml`, `Dockerfile`, `docker-compose.yml`; enforcer `requireReleaseDeps` | `mvnw verify` (enforcer) |
