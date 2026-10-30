# FitClub

Учебное микросервисное backend-приложение фитнес-клуба: пользователи и роли, расписание групповых тренировок, абонементы, бронирования, тестовые платежи и уведомления. Шесть независимых Spring Boot сервисов, API Gateway, RabbitMQ, PostgreSQL и небольшой веб-интерфейс для демонстрации.

> **Состояние: шаг 2 из 11.** Готовы инфраструктура (PostgreSQL с шестью изолированными БД, RabbitMQ с топологией), общий технический модуль и **каркасы шести сервисов**. Каждый сервис имеет свою БД со схемой Flyway, JSON-логи, единый формат ошибок, проверку JWT и служебных токенов, `/health`, `/ready` и Swagger. Бизнес-API появляется со следующими шагами, план — в [docs/progress.md](docs/progress.md).

## Документация

| Файл | Содержание |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Границы шести сервисов, схема компонентов, владение данными, изоляция БД |
| [docs/api.md](docs/api.md) | Контракт REST, формат ошибок, `Idempotency-Key`, ссылки на Swagger |
| [docs/events.md](docs/events.md) | Контракты событий, топология RabbitMQ, гарантии доставки |
| [docs/decisions.md](docs/decisions.md) | Проверенные версии, ключевые решения, допущения |
| [docs/progress.md](docs/progress.md) | План шагов, текущее состояние, последние проверки |

## Структура

```
lab2/
├── pom.xml, mvnw, mvnw.cmd, .mvn/      родительский POM (только версии) и Maven Wrapper
├── docker-compose.yml                  инфраструктура + шесть сервисов
├── docker-compose.dev.yml              dev-override: порты сервисов 8081–8086 на 127.0.0.1
├── libs/
│   ├── fitclub-common/                 техника: JSON-логи, X-Request-ID, problem+json, JWT, X-Service-Token, health
│   └── fitclub-test-support/           только для тестов: PostgreSQL Testcontainers, тестовые JWT, ArchUnit-правила
├── services/
│   └── <name>-service/                 auth, schedule, membership, booking, payment, notification
│       ├── pom.xml, Dockerfile
│       ├── src/main/resources/application*.yml, db/migration/V1__*.sql
│       └── src/test/...                ArchitectureTest, *ServiceSkeletonIT
├── infrastructure/                     bootstrap БД, топология RabbitMQ, генератор секретов
├── gateway/, frontend/, tests/         появятся на шагах 4, 10, 11
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

## Адреса (dev-режим)

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

Бизнес-endpoint'ов на шаге 2 ещё нет, поэтому Swagger показывает пустой список операций.

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
