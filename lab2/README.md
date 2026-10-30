# FitClub

Учебное микросервисное backend-приложение фитнес-клуба: пользователи и роли, расписание групповых тренировок, абонементы, бронирования, тестовые платежи и уведомления. Шесть независимых Spring Boot сервисов, API Gateway, RabbitMQ, PostgreSQL и небольшой веб-интерфейс для демонстрации.

> **Состояние: шаг 1 из 11.** Готовы каркас репозитория и инфраструктура (PostgreSQL с шестью изолированными БД, RabbitMQ с топологией), а также контракты API и событий. Сервисы появляются со следующими шагами, план — в [docs/progress.md](docs/progress.md).

## Документация

| Файл | Содержание |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Границы шести сервисов, схема компонентов, владение данными, изоляция БД |
| [docs/api.md](docs/api.md) | Контракт REST, формат ошибок, `Idempotency-Key` |
| [docs/events.md](docs/events.md) | Контракты событий, топология RabbitMQ, гарантии доставки |
| [docs/decisions.md](docs/decisions.md) | Проверенные версии, ключевые решения, допущения |
| [docs/progress.md](docs/progress.md) | План шагов, текущее состояние, последние проверки |

## Стек

Java 21 LTS, Spring Boot 4.1.2, Spring Cloud 2025.1.4 (Gateway), Spring Security (JWT RS256), Spring Data JPA + Flyway, PostgreSQL 17, RabbitMQ 4.1 + Spring AMQP, springdoc-openapi 3.2.0, JUnit Jupiter 6 / Mockito / Testcontainers / WireMock / ArchUnit, Nginx + HTML/CSS/JS, Docker Compose. Причины выбора и проверка совместимости описаны в [docs/decisions.md](docs/decisions.md).

## Требования к окружению

- **JDK 21** (например, Eclipse Temurin 21). Maven ставить не нужно: используется Maven Wrapper (`mvnw` / `mvnw.cmd`).
- **Docker Desktop** с Docker Compose v2. На Windows Docker Desktop должен быть запущен в режиме Linux-контейнеров (WSL 2 backend).
- Свободные локальные порты `5432`, `5672`, `15672`. Их можно поменять в `.env`, см. `.env.example`.

Проверка окружения:

```powershell
java -version
docker version
docker compose version
```

## Первый запуск (шаг 1: инфраструктура)

Все команды выполняются из каталога `lab2`.

**1. Сгенерировать локальные секреты.** Будут созданы пароли БД, пароли RabbitMQ, межсервисные токены и RSA-ключи JWT. Файлы появятся в `secrets/`, этот каталог исключён из Git. Существующие файлы не перезаписываются, значения на экран не выводятся. Если Java не установлена, скрипт запускает генератор в контейнере JDK.

Windows PowerShell:

```powershell
.\infrastructure\scripts\init-secrets.ps1
```

Bash:

```bash
sh infrastructure/scripts/init-secrets.sh
```

**2. Поднять инфраструктуру:**

```bash
docker compose up -d
```

```bash
docker compose ps
```

`postgres` и `rabbitmq` должны быть в состоянии `healthy`, `postgres-bootstrap` — `exited (0)`.

## Проверка шага 1

Лог bootstrap. Должны быть шесть строк `..._db ready` и `bootstrap: done`:

```bash
docker compose logs postgres-bootstrap
```

Изоляция БД: каждая роль подключается только к своей БД. Ожидаемый результат — 42 строки `OK` и `isolation check passed`:

```bash
docker compose run --rm --entrypoint sh postgres-bootstrap /bootstrap/verify-isolation.sh
```

Сборка родительского POM: проверяются версии Java и Maven и отсутствие SNAPSHOT-зависимостей.

Windows PowerShell:

```powershell
.\mvnw.cmd -q validate
```

Bash:

```bash
./mvnw -q validate
```

Топология RabbitMQ: откройте http://localhost:15672 и войдите как `fitclub_admin`, пароль лежит в `secrets/rabbitmq_admin_password`. Во вкладке **Queues** (vhost `fitclub`) должны быть шесть очередей: три рабочие и три `.dlq`. Проверить можно и командой:

```bash
docker compose exec rabbitmq rabbitmqctl list_queues -p fitclub name type
```

## Остановка и повторный запуск

```bash
docker compose down
```

```bash
docker compose up -d
```

Данные сохраняются в Docker volumes `fitclub_postgres-data` и `fitclub_rabbitmq-data`. **Не используйте `docker compose down -v`**, если не хотите удалить все данные.

## Типовые неисправности

| Симптом | Причина и решение |
|---|---|
| `secret file ... not found` при `docker compose up` | Секреты не сгенерированы. Выполните шаг 1 «Первого запуска» |
| `port is already allocated` | Порт занят, например локальным PostgreSQL. Задайте другой в `.env`: `POSTGRES_HOST_PORT=15432` |
| `postgres-bootstrap` упал с `missing secret` | Удалены отдельные файлы в `secrets/`. Перезапустите генератор, он создаст недостающие |
| `bootstrap-databases.sh: not found` / `\r` в ошибке | Скрипт сохранён с CRLF. В репозитории `.gitattributes` фиксирует LF. Выполните `git add --renormalize .` или пересохраните файл с LF |
| RabbitMQ не стартует после ручной правки `secrets/rabbitmq_users.json` | Удалите этот файл и запустите генератор снова. Файл будет пересоздан из паролей |
