# FitClub

Учебное микросервисное приложение фитнес-клуба:
- пользователи и роли;
- расписание групповых тренировок;
- абонементы и тестовые платежи;
- запись на занятия;
- уведомления и напоминания.

Шесть независимых Spring Boot сервисов, API Gateway, RabbitMQ, PostgreSQL и небольшой веб-интерфейс на Nginx. Всё запускается одной командой Docker Compose.

> **Состояние: все 11 шагов выполнены.** `mvnw verify` — 270 тестов, сквозные проверки через Gateway (`tests/e2e`) — 9 сценариев, включая остановку Notification, Membership и RabbitMQ. Результаты — в [docs/testing.md](docs/testing.md), материалы для защиты — в [docs/defense.md](docs/defense.md).
>
> **Лабораторная 3** (оптимизированные образы, Kubernetes, Minikube) — в [../lab3/README.md](../lab3/README.md). Образы и Compose из этого каталога используются там без изменений.

## Документация

| Файл | Содержание |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Границы шести сервисов, схема компонентов, владение данными, изоляция БД, компромиссы локального окружения |
| [docs/flows.md](docs/flows.md) | Оплата и активация, запись, отмена: REST, события, транзакции, eventual consistency, сбои |
| [docs/events.md](docs/events.md) | Версионированные контракты событий, routing keys, очереди, retry/DLQ, outbox, идемпотентность |
| [docs/api.md](docs/api.md) | Маршруты, примеры, формат ошибок, `Idempotency-Key`, ссылки на OpenAPI |
| [docs/security.md](docs/security.md) | Роли, владение ресурсами, JWT, refresh, служебные токены, секреты, UI, ограничения |
| [docs/solid.md](docs/solid.md) | Пять принципов SOLID на реальных классах, проверки ArchUnit и тесты |
| [docs/testing.md](docs/testing.md) | Стратегия, команды, конкурентность и сбои, фактические результаты |
| [docs/requirements-matrix.md](docs/requirements-matrix.md) | Требование → где реализовано → как проверить |
| [docs/defense.md](docs/defense.md) | Сценарий защиты и ответы на вопросы |
| [docs/decisions.md](docs/decisions.md) | Проверенные версии, решения D1–D70, допущения |
| [docs/progress.md](docs/progress.md) | Выполненные шаги и последние проверки |

## Сервисы

| Сервис | Отвечает за | Своя БД | Взаимодействие |
|---|---|---|---|
| auth-service | Регистрация, вход, JWT RS256, refresh, роли | `auth_db` | → внутренний trainer-status для Schedule |
| schedule-service | Типы тренировок, занятия DRAFT → PUBLISHED → CANCELLED | `schedule_db` | REST → Auth; события `session.published/cancelled` |
| membership-service | Тарифы, заказы, абонементы, право на посещение | `membership_db` | внутренние API для Payment и Booking; слушает `payment.*`; событие `membership.activated` |
| payment-service | Платежи, тестовый провайдер (симулятор), идемпотентность | `payment_db` | REST → Membership; события `payment.succeeded/declined` |
| booking-service | Места, запись, лимит посещений, отмена | `booking_db` | REST → Membership (eligibility); слушает `session.*`; события `booking.*` |
| notification-service | Уведомления и сохраняемые напоминания | `notification_db` | слушает `membership.activated`, `booking.confirmed/cancelled` (отмена занятия приходит как отмена записей) |

API Gateway, Nginx (UI), PostgreSQL и RabbitMQ — инфраструктура, бизнес-правил в них нет. Подробно — в [docs/architecture.md](docs/architecture.md).

## Структура

```
lab2/
├── pom.xml, mvnw, mvnw.cmd, .mvn/      родительский POM (только версии) и Maven Wrapper
├── docker-compose.yml                  инфраструктура, шесть сервисов, gateway, frontend, demo-seed (профиль demo)
├── docker-compose.dev.yml              dev-override: порты сервисов 8081–8086 на 127.0.0.1
├── .env.example                        порты и профили (секретов нет)
├── libs/
│   ├── fitclub-common/                 техника: JSON-логи, X-Request-ID, problem+json, JWT, X-Service-Token, health, HTTP-клиент
│   ├── fitclub-messaging/              конверт события, transactional outbox, relay, обработка с retry/DLQ
│   └── fitclub-test-support/           только для тестов: Testcontainers, тестовые JWT, ArchUnit-правила
├── services/<name>-service/            auth, schedule, membership, booking, payment, notification:
│                                       pom.xml, Dockerfile, api/application/domain/infrastructure, миграции, тесты
├── gateway/                            API Gateway (Spring Cloud Gateway, WebFlux)
├── frontend/                           UI: default.conf.template (Nginx), Dockerfile, static/ (HTML, CSS, ES-модули JS без сборки)
├── infrastructure/                     bootstrap БД и ролей, топология RabbitMQ, генератор секретов, demo/seed.py
├── tests/e2e/                          сквозные проверки через Gateway и скрипты изолированного стенда
└── docs/
```

## Стек и причины выбора

| Технология | Почему |
|---|---|
| Java 21 LTS, Spring Boot 4.1.2 | Типизация, DI, зрелая экосистема для REST, безопасности, данных и тестов |
| Spring Security, JWT RS256 | Сервисы проверяют токен локально по публичному ключу, закрытый ключ только у Auth |
| Spring Data JPA, Flyway, PostgreSQL 17 | Транзакции и блокировки строк для мест и лимитов, версионированные миграции, шесть логических БД на одном сервере |
| RabbitMQ 4.1, Spring AMQP | Асинхронные события с подтверждениями, quorum-очереди, DLQ |
| Spring Cloud Gateway 2025.1.4 | Единая точка входа, проверка JWT до сервисов, закрытие `/internal` |
| springdoc-openapi 3.2.0 | OpenAPI и Swagger UI для каждого сервиса и общий UI в Gateway |
| JUnit 6, Testcontainers, WireMock, ArchUnit, Awaitility | Правила, настоящие PostgreSQL/RabbitMQ в тестах, контроль архитектуры, ожидание асинхронности |
| Nginx + HTML/CSS/JS | UI без сборки и фреймворка, тот же origin для `/api` |
| Docker Compose | Воспроизводимый запуск без облачных аккаунтов |

Проверка совместимости версий — в [docs/decisions.md](docs/decisions.md).

## Требования к окружению

- **JDK 21** (например, Eclipse Temurin 21). Maven ставить не нужно: используется Maven Wrapper (`mvnw` / `mvnw.cmd`).
- **Docker Desktop** с Docker Compose v2. На Windows Docker Desktop должен быть запущен и работать с Linux-контейнерами (WSL 2 backend). Рекомендуется не меньше 6–8 ГБ памяти для Docker.
- Свободные локальные порты `8080` (UI), `8000` (Gateway), `5432`, `5672`, `15672`, для dev-режима ещё `8081`–`8086`. Порты меняются в `.env`, см. `.env.example`.

Проверка окружения:

```powershell
java -version
docker version
docker compose version
```

## Первый запуск

Все команды выполняются из каталога `lab2`.

**1. Сгенерировать локальные секреты.** Будут созданы:
- пароли БД и RabbitMQ;
- межсервисные токены;
- RSA-ключи JWT;
- пароль первого администратора;
- общий пароль demo-пользователей.

Файлы появятся в `secrets/`, этот каталог исключён из Git. Существующие файлы не перезаписываются, значения на экран не выводятся.

```powershell
.\infrastructure\scripts\init-secrets.ps1
```

```bash
sh infrastructure/scripts/init-secrets.sh
```

**2. Собрать и запустить стенд в demo-режиме.** Профиль `demo` включает тестовое завершение оплаты (`POST /api/payments/{id}/simulate`). Первая сборка занимает несколько минут: в контейнере скачиваются Maven и зависимости.

Windows PowerShell:

```powershell
$env:FITCLUB_PROFILES = "docker,demo"
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

Bash:

```bash
FITCLUB_PROFILES=docker,demo docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

Вместо переменной можно раскомментировать строку `FITCLUB_PROFILES=docker,demo` в `.env` (скопируйте `.env.example` в `.env`). Без профиля demo стенд работает в рабочем режиме: оплату нельзя завершить тестовой кнопкой.

```bash
docker compose ps
```

Все `*-service`, `gateway`, `frontend`, `postgres` и `rabbitmq` должны быть `healthy`, `postgres-bootstrap` — `Exited (0)`. Без `docker-compose.dev.yml` порты сервисов 8081–8086 не публикуются: внешний вход — только UI и Gateway.

**3. Заполнить demo-данными** (только по явной команде; повторный запуск ничего не дублирует):

```bash
docker compose --profile demo run --rm demo-seed
```

**4. Открыть http://localhost:8080.**

## Адреса

| Что | Адрес |
|---|---|
| **Интерфейс** (публичный вход; `/api` и Swagger проксируются в Gateway) | http://localhost:8080 |
| API Gateway | http://localhost:8000/api/... |
| Общий Swagger UI (шесть сервисов, выбор в списке справа сверху) | http://localhost:8000/swagger-ui.html или http://localhost:8080/swagger-ui.html |
| Health / Ready Gateway | http://localhost:8000/health, http://localhost:8000/ready |
| RabbitMQ Management | http://localhost:15672 (`fitclub_admin`, пароль в `secrets/rabbitmq_admin_password`) |

**Служебные адреса сервисов (только dev-режим, 127.0.0.1):**

| Сервис | Порт | Health / Ready | Swagger UI | OpenAPI JSON |
|---|---|---|---|---|
| auth-service | 8081 | http://localhost:8081/health, `/ready` | http://localhost:8081/swagger-ui.html | http://localhost:8081/v3/api-docs |
| schedule-service | 8082 | http://localhost:8082/health, `/ready` | http://localhost:8082/swagger-ui.html | http://localhost:8082/v3/api-docs |
| membership-service | 8083 | http://localhost:8083/health, `/ready` | http://localhost:8083/swagger-ui.html | http://localhost:8083/v3/api-docs |
| booking-service | 8084 | http://localhost:8084/health, `/ready` | http://localhost:8084/swagger-ui.html | http://localhost:8084/v3/api-docs |
| payment-service | 8085 | http://localhost:8085/health, `/ready` | http://localhost:8085/swagger-ui.html | http://localhost:8085/v3/api-docs |
| notification-service | 8086 | http://localhost:8086/health, `/ready` | http://localhost:8086/swagger-ui.html | http://localhost:8086/v3/api-docs |

`/health` — liveness процесса. `/ready` — готовность: собственная БД доступна и миграции применены. От RabbitMQ `/ready` не зависит: без брокера операции продолжают работать через outbox.

## Учётные записи

| Кто | Email | Пароль |
|---|---|---|
| Администратор (создаётся auth-service при первом запуске) | `admin@fitclub.local` (переменная `FITCLUB_AUTH_INITIAL_ADMIN_EMAIL`) | `secrets/auth_initial_admin_password` |
| Тренеры (demo) | `trainer.anna@fitclub.demo`, `trainer.boris@fitclub.demo` | `secrets/demo_users_password` |
| Клиенты (demo) | `client.elena@fitclub.demo` (абонемент «Стандарт», записана на «Утреннюю йогу»), `client.dmitry@fitclub.demo` (безлимит), `client.olga@fitclub.demo` (без абонемента) | `secrets/demo_users_password` |

```powershell
Get-Content secrets\auth_initial_admin_password
Get-Content secrets\demo_users_password
```

Demo-сидер также создаёт тарифы «Старт — 4 посещения», «Стандарт — 8 посещений», «Безлимит» и типы «Йога», «Пилатес», «Функциональный тренинг». Пять занятий публикуются на три дня вперёд **от момента запуска сидера**, одно из них — на одно место. Сидер работает только через публичный API, в базы напрямую не пишет. Новый пользователь регистрируется в UI и получает роль клиента.

## Пользовательский сценарий

| Роль | Что сделать в UI |
|---|---|
| Клиент (Ольга или новая регистрация) | «Тарифы» → «Оформить» → «Оплатить (тест: успешно)» → «Активация абонемента…» → активирован. «Расписание» → «Записаться» (свободные места уменьшаются). «Мой абонемент» — остаток посещений. «Мои записи» → «Отменить запись» (не позже чем за 120 минут до начала, иначе показан срок). «Уведомления» и счётчик в шапке |
| Тренер (Анна) | «Мои занятия» → «Участники» |
| Администратор | «Занятия»: тип тренировки, черновик, правка черновика, публикация, отмена с причиной (записи отменяются, посещения возвращаются). «Тарифы клуба»: создание, снятие с продажи. «Пользователи»: поиск и назначение ролей |

Что происходит за кадром: оплата → событие `payment.succeeded` → Membership активирует абонемент → `membership.activated` → уведомление. Запись проверяет право через REST-вызов Booking → Membership и публикует `booking.confirmed`. Сценарии с транзакциями и сбоями — в [docs/flows.md](docs/flows.md).

## Основные endpoint'ы

Все маршруты — через Gateway с `Authorization: Bearer <access_token>`, кроме регистрации, входа и refresh. Полный список с примерами — в [docs/api.md](docs/api.md) и Swagger.

| Сервис | Маршрут | Кто |
|---|---|---|
| auth | `POST /api/auth/register`, `/login`, `/refresh`, `/logout`; `GET /api/auth/me` | все |
| auth | `GET /api/admin/users?role&q`, `PATCH /api/admin/users/{id}/roles` | ADMIN |
| schedule | `GET /api/training-types`, `GET /api/sessions?from&to`, `GET /api/sessions/{id}` | авторизованный |
| schedule | `POST /api/training-types`, `POST /api/sessions`, `PATCH /api/sessions/{id}` (черновик), `POST /api/sessions/{id}/publish`, `/cancel` | ADMIN |
| schedule | `GET /api/sessions/mine` | TRAINER |
| membership | `GET /api/plans`; `POST/PATCH /api/plans` (ADMIN) | авторизованный |
| membership | `POST /api/membership-orders` (`Idempotency-Key`), `GET /api/membership-orders/{id}`, `GET /api/memberships/me` | владелец |
| payment | `POST /api/payments` (`Idempotency-Key`), `GET /api/payments/{id}`; `POST /api/payments/{id}/simulate` (только профиль demo) | владелец |
| booking | `GET /api/availability?session_id=…` | авторизованный |
| booking | `POST /api/bookings` (`Idempotency-Key`), `POST /api/bookings/{id}/cancel`, `GET /api/bookings/me`, `GET /api/bookings/usage/me` | владелец |
| booking | `GET /api/sessions/{id}/bookings` | назначенный TRAINER, ADMIN |
| notification | `GET /api/notifications?unread`, `GET /api/notifications/unread-count`, `PATCH /api/notifications/{id}/read` | получатель |

Ошибки во всех сервисах — `application/problem+json` с полями `code` и `request_id`. Внутренние маршруты `/internal/**` через Gateway недоступны (404) и требуют `X-Service-Token` своего направления.

Пример из терминала (тело с кириллицей надёжнее передавать файлом в UTF-8):

```powershell
curl.exe -s -X POST http://localhost:8000/api/auth/login -H "Content-Type: application/json" --data-binary "@login.json"
```

## Тесты

Модульные, архитектурные и интеграционные тесты всех модулей. Нужен запущенный Docker Desktop: Testcontainers поднимает PostgreSQL и RabbitMQ сам.

```powershell
.\mvnw.cmd verify
```

```bash
./mvnw verify
```

Только модульные и архитектурные, без Docker: `.\mvnw.cmd test`.

Сквозные проверки через Gateway. Скрипт поднимает **изолированный** стенд `fitclub-e2e` (свои контейнеры, volumes и порты 18000/18080), запускает `tests/e2e` и останавливает стенд. Данные основного стенда не меняются:

```powershell
.\tests\e2e\run-e2e.ps1
```

```bash
sh tests/e2e/run-e2e.sh
```

Последний результат: `mvnw verify` — 270 тестов, 0 падений; E2E — 9 тестов, 0 падений. Что именно проверяется (конкурентность, повторы событий, сбои) — в [docs/testing.md](docs/testing.md).

## Сценарии сбоев вручную

| Команда | Что увидеть |
|---|---|
| `docker compose stop notification-service` | Запись в UI проходит, раздел «Уведомления» — «Сервис временно недоступен». После `docker compose start notification-service` уведомление появляется: событие ждало в очереди |
| `docker compose stop membership-service` | «Записаться» → «Сервис временно недоступен» (503), место не занято. После запуска запись проходит |
| `docker compose stop rabbitmq` | Тестовая оплата проходит, UI показывает «абонемент ещё активируется». Событие ждёт в `outbox_events` payment_db. После `docker compose start rabbitmq` абонемент активируется один раз |
| `docker compose stop gateway` | Страница UI открывается, API отвечает `503 DEPENDENCY_UNAVAILABLE` |
| `docker compose stop postgres` | Через 5–10 с `/ready` сервисов → 503, `/health` → 200 |

Неотправленные события и очереди:

```bash
docker compose exec postgres psql -U postgres -d payment_db -c "select event_type, published_at, attempts from outbox_events order by occurred_at desc limit 5"
```

```bash
docker compose exec rabbitmq rabbitmqctl list_queues -p fitclub name messages
```

Почему система ведёт себя именно так — в [docs/defense.md](docs/defense.md).

## Запуск сервиса из VS Code или терминала

Инфраструктура остаётся в Docker, сервис запускается на машине с профилем `local` (по умолчанию). Он подключается к `localhost:5432` и читает секреты из `secrets/`.

1. Поднимите инфраструктуру:

   ```bash
   docker compose up -d postgres postgres-bootstrap rabbitmq
   ```

   Если этот же сервис уже запущен в Docker с dev-override, остановите контейнер, чтобы освободить порт, например `docker compose stop auth-service`.
2. **VS Code:** откройте `lab2`, установите Extension Pack for Java, затем Run and Debug → выберите, например, `auth-service (8081)`. Конфигурации лежат в `.vscode/launch.json`.
3. **Терминал:** один раз выполните `.\mvnw.cmd install -DskipTests`, затем:

   ```powershell
   .\mvnw.cmd -pl services/auth-service spring-boot:run
   ```

Адрес БД любого сервиса меняется без правки кода, переменной `FITCLUB_DB_URL`, например `jdbc:postgresql://other-host:5432/auth_db`.

## Логи, остановка, пересборка одного сервиса

Логи одного сервиса в реальном времени (JSON: `timestamp`, `service`, `level`, `request_id`, `message`):

```bash
docker compose logs -f booking-service
```

Один запрос во всех сервисах — по `X-Request-ID`:

```bash
curl -s http://localhost:8000/api/plans -H "X-Request-ID: my-trace-000001"
```

```powershell
docker compose logs gateway membership-service | findstr my-trace-000001
```

В Bash вместо `findstr` используйте `grep`.

Пересобрать и перезапустить только один сервис:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build booking-service
```

Остановить всё без потери данных и запустить снова:

```bash
docker compose stop
```

```bash
docker compose start
```

`docker compose down` удаляет контейнеры, но сохраняет данные. Данные лежат в Docker volumes `fitclub_postgres-data` и `fitclub_rabbitmq-data`. **Не используйте `docker compose down -v`**, если не хотите удалить все данные.

## Типовые неисправности

| Симптом | Причина и решение |
|---|---|
| `secret file ... not found` при `docker compose up` | Секреты не сгенерированы (или добавился новый, например `demo_users_password`). Запустите генератор из шага 1: он добавит только недостающие файлы |
| `failed to connect to the docker API` / `dockerDesktopLinuxEngine` | Docker Desktop не запущен. Запустите его и дождитесь статуса Engine running |
| `port is already allocated` | Порт занят, например локальным PostgreSQL или сервисом из IDE. Задайте другой в `.env` (`POSTGRES_HOST_PORT=15432`, `UI_HOST_PORT=8090`) или остановите конфликтующий процесс |
| Сервис долго `unhealthy` / перезапускается | `docker compose logs <service>`. Частая причина — БД недоступна или `postgres-bootstrap` завершился с ошибкой |
| В UI после оплаты: «Тестовая оплата выключена» | Стенд запущен без профиля demo. Перезапустите с `FITCLUB_PROFILES=docker,demo` |
| Абонемент «ещё активируется» дольше минуты | Проверьте RabbitMQ (`docker compose ps rabbitmq`) и неотправленные события в `outbox_events` (см. «Сценарии сбоев») |
| При запуске из IDE: `Could not resolve placeholder 'db_auth_password'` | Рабочий каталог не `lab2`, и сервис не видит `secrets/`. Используйте `.vscode/launch.json` или задайте `FITCLUB_SECRETS_DIR` с абсолютным путём |
| `mvnw verify`: `Could not find a valid Docker environment` | Docker Desktop не запущен |
| `bootstrap-databases.sh: not found` / `\r` в ошибке | Скрипт сохранён с CRLF. В репозитории `.gitattributes` фиксирует LF. Выполните `git add --renormalize .` или пересохраните файл с LF |
| RabbitMQ не стартует после ручной правки `secrets/rabbitmq_users.json` | Удалите этот файл и запустите генератор снова. Файл будет пересоздан из паролей |
| Русский текст в выводе `.ps1` искажён | Скрипт сохранён без BOM. Файлы `.ps1` в репозитории — UTF-8 с BOM, сохраняйте их так же |
