# FitClub

Учебное микросервисное backend-приложение фитнес-клуба: пользователи и роли, расписание групповых тренировок, абонементы, бронирования, тестовые платежи и уведомления. Шесть независимых Spring Boot сервисов, API Gateway, RabbitMQ, PostgreSQL и небольшой веб-интерфейс для демонстрации.

> **Состояние: шаг 9 из 11.** Реализованы **все шесть бизнес-сервисов**: auth, schedule, membership, payment, booking и notification. Работают API Gateway (`http://localhost:8000`) и сквозные сценарии через REST и RabbitMQ: абонемент → оплата → запись → уведомления и напоминания. UI — шаг 10, итоговые проверки и документация для защиты — шаг 11, план — в [docs/progress.md](docs/progress.md).

## Документация

| Файл | Содержание |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Границы шести сервисов, схема компонентов, владение данными, изоляция БД |
| [docs/api.md](docs/api.md) | Контракт REST, формат ошибок, `Idempotency-Key`, ссылки на Swagger |
| [docs/events.md](docs/events.md) | Контракты событий, топология RabbitMQ, outbox, потребители, DLQ |
| [docs/flows.md](docs/flows.md) | Сценарии с REST и событиями, транзакции, eventual consistency, сбои |
| [docs/security.md](docs/security.md) | Роли, пароли, JWT, refresh-токены, служебные токены, секреты, ограничения |
| [docs/decisions.md](docs/decisions.md) | Проверенные версии, ключевые решения, допущения |
| [docs/progress.md](docs/progress.md) | План шагов, текущее состояние, последние проверки |

## Структура

```
lab2/
├── pom.xml, mvnw, mvnw.cmd, .mvn/      родительский POM (только версии) и Maven Wrapper
├── docker-compose.yml                  инфраструктура + шесть сервисов
├── docker-compose.dev.yml              dev-override: порты сервисов 8081–8086 на 127.0.0.1
├── libs/
│   ├── fitclub-common/                 техника: JSON-логи, X-Request-ID, problem+json, JWT, X-Service-Token, health, HTTP-клиент
│   ├── fitclub-messaging/              конверт события, transactional outbox, relay в RabbitMQ
│   └── fitclub-test-support/           только для тестов: PostgreSQL Testcontainers, тестовые JWT, ArchUnit-правила
├── services/
│   └── <name>-service/                 auth, schedule, membership, booking, payment, notification
│       ├── pom.xml, Dockerfile
│       ├── src/main/resources/application*.yml, db/migration/V1__*.sql
│       └── src/test/...                ArchitectureTest, *ServiceSkeletonIT
├── infrastructure/                     bootstrap БД, топология RabbitMQ, генератор секретов
├── gateway/                            API Gateway (Spring Cloud Gateway, WebFlux)
├── frontend/, tests/                   появятся на шагах 10, 11
└── docs/
```

## Стек

Java 21 LTS, Spring Boot 4.1.2, Spring Cloud 2025.1.4 (Gateway), Spring Security (JWT RS256), Spring Data JPA + Flyway, PostgreSQL 17, RabbitMQ 4.1 + Spring AMQP, springdoc-openapi 3.2.0, JUnit Jupiter 6 / Mockito / Testcontainers / WireMock / ArchUnit, Nginx + HTML/CSS/JS, Docker Compose. Причины выбора и проверка совместимости описаны в [docs/decisions.md](docs/decisions.md).

## Требования к окружению

- **JDK 21** (например, Eclipse Temurin 21). Maven ставить не нужно: используется Maven Wrapper (`mvnw` / `mvnw.cmd`).
- **Docker Desktop** с Docker Compose v2. На Windows Docker Desktop должен быть запущен в режиме Linux-контейнеров (WSL 2 backend).
- Свободные локальные порты `5432`, `5672`, `15672`, для dev-режима ещё `8081`–`8086`. Порты инфраструктуры можно поменять в `.env`, см. `.env.example`.

Проверка окружения:

```powershell
java -version
docker version
docker compose version
```

## Первый запуск

Все команды выполняются из каталога `lab2`.

**1. Сгенерировать локальные секреты.** Будут созданы пароли БД, пароли RabbitMQ, межсервисные токены и RSA-ключи JWT. Файлы появятся в `secrets/`, этот каталог исключён из Git. Существующие файлы не перезаписываются, значения на экран не выводятся.

Windows PowerShell:

```powershell
.\infrastructure\scripts\init-secrets.ps1
```

Bash:

```bash
sh infrastructure/scripts/init-secrets.sh
```

**2. Собрать образы и запустить всё вместе с портами сервисов.** Первая сборка занимает несколько минут: в контейнере скачиваются Maven и зависимости.

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

```bash
docker compose ps
```

Все `*-service`, `postgres` и `rabbitmq` должны быть `healthy`, `postgres-bootstrap` — `Exited (0)`.

Без `docker-compose.dev.yml` сервисы тоже запускаются, но их порты не публикуются: в итоговой схеме внешний вход — только UI и Gateway.

## Адреса

**Основной вход — API Gateway:**

| Что | Адрес |
|---|---|
| API | http://localhost:8000/api/... |
| Общий Swagger UI (все шесть сервисов, выбор в списке справа сверху) | http://localhost:8000/swagger-ui.html |
| Health / Ready Gateway | http://localhost:8000/health, http://localhost:8000/ready |

**Служебные адреса сервисов (dev-режим):**

| Сервис | Health / Ready | Swagger UI | OpenAPI JSON |
|---|---|---|---|
| auth-service | http://localhost:8081/health, `/ready` | http://localhost:8081/swagger-ui.html | http://localhost:8081/v3/api-docs |
| schedule-service | http://localhost:8082/health, `/ready` | http://localhost:8082/swagger-ui.html | http://localhost:8082/v3/api-docs |
| membership-service | http://localhost:8083/health, `/ready` | http://localhost:8083/swagger-ui.html | http://localhost:8083/v3/api-docs |
| booking-service | http://localhost:8084/health, `/ready` | http://localhost:8084/swagger-ui.html | http://localhost:8084/v3/api-docs |
| payment-service | http://localhost:8085/health, `/ready` | http://localhost:8085/swagger-ui.html | http://localhost:8085/v3/api-docs |
| notification-service | http://localhost:8086/health, `/ready` | http://localhost:8086/swagger-ui.html | http://localhost:8086/v3/api-docs |
| RabbitMQ Management | http://localhost:15672 (`fitclub_admin`, пароль в `secrets/rabbitmq_admin_password`) | | |

- `/health` — liveness процесса.
- `/ready` — готовность: собственная БД доступна и все миграции применены.

Бизнес-endpoint'ы пока есть только у auth-service, у остальных Swagger показывает пустой список операций.

## Первый администратор

При запуске auth-service создаёт администратора, если его ещё нет:

- email — `admin@fitclub.local` (меняется переменной `FITCLUB_AUTH_INITIAL_ADMIN_EMAIL` в `.env`);
- пароль — в файле `secrets/auth_initial_admin_password`. Его создаёт генератор секретов; если файла нет, запустите генератор ещё раз.

Посмотреть пароль:

```powershell
Get-Content secrets\auth_initial_admin_password
```

## Проверка шага 3 (auth-service)

Удобнее всего в общем Swagger UI Gateway: http://localhost:8000/swagger-ui.html (в списке справа сверху выберите `auth-service`).

1. `POST /api/auth/register` — зарегистрируйте пользователя (роль будет `CLIENT`, даже если добавить в тело `"roles":["ADMIN"]`).
2. `POST /api/auth/login` под `admin@fitclub.local` → скопируйте `access_token` → кнопка **Authorize** → вставьте токен.
3. `GET /api/admin/users` — список пользователей. `PATCH /api/admin/users/{id}/roles` с `{"roles":["CLIENT","TRAINER"]}` — назначение тренера.
4. `POST /api/auth/refresh` с `refresh_token` выдаёт новую пару. Повторный refresh **старым** токеном вернёт `401` и отзовёт всю цепочку.
5. `POST /api/auth/logout` отзывает refresh-токен. Access-токен действует до своего срока (15 минут), см. [docs/security.md](docs/security.md).

Из терминала тело запроса с кириллицей надёжнее передавать файлом в UTF-8: создайте `body.json` в VS Code и выполните:

```powershell
curl.exe -s -X POST http://localhost:8081/api/auth/register -H "Content-Type: application/json" --data-binary "@body.json"
```

## Проверка шага 2

Ответ без токена: `401` в формате `application/problem+json` с `request_id`.

```bash
curl -i http://localhost:8084/api/bookings/me -H "X-Request-ID: demo-request-0001"
```

Внутренний маршрут без служебного токена: `401 INVALID_SERVICE_CREDENTIAL`.

```bash
curl http://localhost:8083/internal/memberships/eligibility
```

JSON-лог этого запроса (`timestamp`, `service`, `level`, `request_id`, `message`):

```bash
docker compose logs booking-service
```

Каждый сервис применил миграции под собственной ролью:

```bash
docker compose exec postgres psql -U postgres -d booking_db -c "select version, description, installed_by from flyway_schema_history"
```

`/ready` падает при потере БД, а `/health` остаётся `UP`. Остановите PostgreSQL:

```bash
docker compose stop postgres
```

Подождите 5–10 секунд и проверьте оба адреса: `/ready` вернёт `503`, `/health` — `200`.

```bash
curl -i http://localhost:8081/ready
```

```bash
curl -i http://localhost:8081/health
```

Верните PostgreSQL:

```bash
docker compose start postgres
```

## Тесты

Модульные и архитектурные тесты (ArchUnit) без Docker:

Windows PowerShell:

```powershell
.\mvnw.cmd test
```

Bash:

```bash
./mvnw test
```

Всё вместе с интеграционными тестами (`*IT`, настоящий PostgreSQL через Testcontainers, нужен запущенный Docker Desktop):

```powershell
.\mvnw.cmd verify
```

```bash
./mvnw verify
```

Подробные результаты последнего прогона — в [docs/progress.md](docs/progress.md).

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

## Проверка шага 4 (API Gateway)

Запрос без токена отклоняет сам Gateway: `401` problem+json.

```bash
curl -i http://localhost:8000/api/plans
```

Внутренние маршруты снаружи не существуют: `404`.

```bash
curl -i http://localhost:8000/internal/users/1/trainer-status
```

Попытка обойти это через `..`: `400`.

```bash
curl -i --path-as-is "http://localhost:8000/api/auth/../../internal/users/1/trainer-status"
```

Недоступный сервис даёт контролируемый `503 DEPENDENCY_UNAVAILABLE`, а не зависание. Остановите сервис, выполните любой запрос к `/api/notifications` с токеном (например, через Swagger UI) и запустите сервис обратно:

```bash
docker compose stop notification-service
```

```bash
docker compose start notification-service
```

Один `request_id` виден в логах Gateway и сервиса:

```bash
curl -s http://localhost:8000/api/plans -H "X-Request-ID: my-trace-000001"
```

```bash
docker compose logs gateway auth-service membership-service | findstr my-trace-000001
```

В Bash вместо `findstr` используйте `grep`.

## Проверка шага 5 (schedule-service и события)

В общем Swagger UI (http://localhost:8000/swagger-ui.html) выберите `schedule-service`, авторизуйтесь токеном администратора и выполните:

1. `POST /api/training-types` с `{"name":"Пилатес"}`.
2. `POST /api/sessions` с `training_type_id`, `trainer_id` (id пользователя с ролью TRAINER из шага 3), временем в будущем и `capacity`. В ответе `status = DRAFT` и `trainer_display_name`: имя пришло из auth-service.
3. `POST /api/sessions/{id}/publish` переводит занятие в `PUBLISHED`, `version = 1`. Попытка `PATCH` после этого вернёт `409 SESSION_NOT_EDITABLE`.

Событие лежит в очереди booking-service, который появится на шаге 8, и не теряется:

```bash
docker compose exec rabbitmq rabbitmqctl list_queues -p fitclub name messages
```

Строка outbox отправлена:

```bash
docker compose exec postgres psql -U postgres -d schedule_db -c "select event_type, aggregate_version, published_at, attempts from outbox_events"
```

Состояние доставки событий — отдельно от готовности HTTP:

```bash
curl http://localhost:8082/actuator/health/messaging
```

Если остановить брокер (`docker compose stop rabbitmq`), публикация занятия всё равно пройдёт: событие сохранится в outbox. `/ready` останется `200`, а `messaging` станет `DOWN`. После `docker compose start rabbitmq` событие будет отправлено.

## Проверка шага 6 (membership-service)

В общем Swagger UI выберите `membership-service`:

1. Под ADMIN: `POST /api/plans` с `{"name":"8 посещений в месяц","duration_days":30,"visit_limit":8,"price_minor":45000}` и безлимитный тариф без `visit_limit`.
2. Под клиентом: `POST /api/membership-orders` с `{"plan_id":"…"}` и заголовком `Idempotency-Key`. Подойдёт любой UUID, например из https://www.uuidgenerator.net. Цена в ответе — из тарифа, даже если добавить в тело `"price_minor":1`.
3. Повторите запрос с тем же ключом: тот же `id` и заголовок `Idempotent-Replayed: true`. Тот же ключ с другим тарифом даёт `409 IDEMPOTENCY_CONFLICT`.
4. Новый ключ, но другой тариф, пока первый заказ не оплачен: `409 PENDING_ORDER_EXISTS`. Оплата появится на шаге 7.

Внутренний API доступен только с токеном своего направления. Через Gateway он закрыт:

```powershell
curl.exe -s http://localhost:8083/internal/membership-orders/<order_id>/payment-details -H "X-Service-Token: $(Get-Content secrets\service_token_payment_to_membership)"
```

## Проверка шага 7 (оплата и активация абонемента)

Тестовая оплата — demo-возможность. Включите профиль demo: создайте файл `.env` рядом с `docker-compose.yml` со строкой

```
FITCLUB_PROFILES=docker,demo
```

и перезапустите сервисы:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

Дальше в общем Swagger UI под **клиентом**:

1. `membership-service` → `POST /api/membership-orders` с `{"plan_id":"…"}` и заголовком `Idempotency-Key`. Скопируйте `id` заказа.
2. `payment-service` → `POST /api/payments` с `{"membership_order_id":"…"}` и новым `Idempotency-Key`: платёж `PENDING`, сумма из тарифа.
3. `POST /api/payments/{id}/simulate` с `{"outcome":"SUCCESS"}`: платёж `SUCCEEDED`. Это тестовая оплата, деньги не списываются.
4. `membership-service` → `GET /api/memberships/me`: через долю секунды абонемент `ACTIVE`. Он появляется асинхронно, по событию `PaymentSucceeded`.

С `{"outcome":"DECLINE"}` заказ станет `PAYMENT_FAILED`, абонемента не будет, и можно оформить новый заказ.

Путь события виден в логах по одному `correlation_id`:

```bash
docker compose logs payment-service membership-service | findstr "PaymentSucceeded MembershipActivated"
```

Очереди и DLQ (`messages` в `.dlq` должно быть 0):

```bash
docker compose exec rabbitmq rabbitmqctl list_queues -p fitclub name messages
```

## Проверка шага 8 (запись на занятия)

Нужны опубликованное будущее занятие (шаг 5) и клиент с активным абонементом (шаг 7). В общем Swagger UI под клиентом выберите `booking-service`:

1. `GET /api/availability?session_id=…` — свободные места (`state: OPEN`).
2. `POST /api/bookings` с `{"session_id":"…"}` и `Idempotency-Key`: запись `CONFIRMED`, свободных мест стало меньше.
3. `GET /api/bookings/usage/me` — использовано и осталось посещений.
4. Повторная запись на то же занятие — `409 ALREADY_BOOKED`. На занятие без мест — `SESSION_FULL`. Без абонемента — `MEMBERSHIP_NOT_VALID`.
5. Под тренером этого занятия: `GET /api/sessions/{id}/bookings` — участники по именам. Под клиентом — `403`.
6. `POST /api/bookings/{id}/cancel` — отмена: место и посещение возвращаются. Позже чем за 120 минут до начала — `CANCELLATION_WINDOW_CLOSED`.
7. Под ADMIN в `schedule-service` `POST /api/sessions/{id}/cancel`: через долю секунды все записи на это занятие отменены (`cancel_reason: SESSION_CANCELLED`), посещения возвращены.

Окно отмены настраивается переменной `CANCEL_BEFORE_MINUTES` (по умолчанию 120).

## Проверка шага 9 (уведомления)

В общем Swagger UI под клиентом выберите `notification-service`:

1. `GET /api/notifications` — уведомления об активации абонемента, записи и отмене (время в тексте — по Кишинёву).
2. `GET /api/notifications/unread-count`, затем `PATCH /api/notifications/{id}/read`: счётчик уменьшается. Чужое уведомление — `404`.

Notification не нужен для записи на занятие, и уведомления не теряются, пока он выключен:

```bash
docker compose stop notification-service
```

Запишитесь на занятие (booking-service), затем:

```bash
docker compose start notification-service
```

Через несколько секунд подтверждение записи появится в `GET /api/notifications`.

Напоминание приходит за `REMINDER_LEAD_MINUTES` (по умолчанию 60) минут до начала. Задачи видны в БД и переживают перезапуск:

```bash
docker compose exec postgres psql -U postgres -d notification_db -c "select status, remind_at, starts_at from reminder_jobs"
```

## Логи, остановка, пересборка одного сервиса

Логи одного сервиса в реальном времени:

```bash
docker compose logs -f auth-service
```

Пересобрать и перезапустить только один сервис:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build auth-service
```

Остановить всё без потери данных:

```bash
docker compose down
```

Данные сохраняются в Docker volumes `fitclub_postgres-data` и `fitclub_rabbitmq-data`. **Не используйте `docker compose down -v`**, если не хотите удалить все данные.

## Типовые неисправности

| Симптом | Причина и решение |
|---|---|
| `secret file ... not found` при `docker compose up` | Секреты не сгенерированы. Выполните шаг 1 «Первого запуска» |
| `port is already allocated` | Порт занят, например локальным PostgreSQL или сервисом из IDE. Задайте другой в `.env` (`POSTGRES_HOST_PORT=15432`) или остановите конфликтующий процесс |
| Сервис в `docker compose ps` долго `unhealthy` / перезапускается | Смотрите `docker compose logs <service>`. Частая причина — БД недоступна или `postgres-bootstrap` завершился с ошибкой |
| При запуске из IDE: `Could not resolve placeholder 'db_auth_password'` | Рабочий каталог не `lab2`, и сервис не видит `secrets/`. Используйте `.vscode/launch.json` или задайте `FITCLUB_SECRETS_DIR` с абсолютным путём |
| `mvnw verify`: ошибки Testcontainers `Could not find a valid Docker environment` | Docker Desktop не запущен |
| `postgres-bootstrap` упал с `missing secret` | Удалены отдельные файлы в `secrets/`. Перезапустите генератор, он создаст недостающие |
| `bootstrap-databases.sh: not found` / `\r` в ошибке | Скрипт сохранён с CRLF. В репозитории `.gitattributes` фиксирует LF. Выполните `git add --renormalize .` или пересохраните файл с LF |
| RabbitMQ не стартует после ручной правки `secrets/rabbitmq_users.json` | Удалите этот файл и запустите генератор снова. Файл будет пересоздан из паролей |
