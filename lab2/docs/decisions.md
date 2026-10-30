# Решения и версии

## Проверка версий

Проверено 30.10.2026 по первоисточникам: метаданные Maven Central, BOM-файлы Spring Boot и springdoc, API `start.spring.io/actuator/info` с диапазонами `bom-ranges`, Docker Hub. Это **заявленная** совместимость. На шаге 1 фактически проверены Maven Wrapper (`validate`), образы PostgreSQL и RabbitMQ и Temurin 21.0.13. На шаге 2 весь набор собран и протестирован: `mvnw verify`, Testcontainers и `docker compose build` (см. [progress.md](progress.md)).

| Компонент | Версия | Источник и основание |
|---|---|---|
| Java | 21 LTS | Требование задания; Spring Boot 4.1 поддерживает Java 17+ |
| Spring Boot | **4.1.2** | Последний GA-релиз на Maven Central. 4.2.0 пока только RC1, поэтому не используется |
| Spring Cloud | **2025.1.4** | `start.spring.io`: `2025.1.4` → «Spring Boot >=4.0.0 and <4.2.0-M1» |
| springdoc-openapi | **3.2.0** | Родительский POM springdoc 3.2.0 собран на `spring-boot-starter-parent 4.1.2` |
| Spring Framework | 7.0.10 | Управляется Boot BOM |
| Spring Security | 7.1.2 | Управляется Boot BOM |
| Spring AMQP | 4.1.2 | Управляется Boot BOM |
| Hibernate ORM | 7.4.13.Final | Управляется Boot BOM |
| Jackson | 3.1.7 (пакеты `tools.jackson.*`) | Управляется Boot BOM. В Boot 4 по умолчанию используется Jackson 3 |
| Flyway | 12.4.0 | Управляется Boot BOM; для PostgreSQL нужен модуль `flyway-database-postgresql` |
| PostgreSQL JDBC | 42.7.14 | Управляется Boot BOM |
| JUnit Jupiter | 6.0.3 | Управляется Boot BOM. Major-версию не меняем ради старых шаблонов |
| Mockito | 5.23.0 | Управляется Boot BOM |
| Testcontainers | 2.0.5 | Управляется Boot BOM |
| Awaitility | 4.3.0 | Управляется Boot BOM |
| ArchUnit | 1.5.1 | Maven Central, последний релиз; Boot BOM им не управляет |
| WireMock | 3.13.2 (`wiremock-standalone`) | Последний стабильный 3.x. Ветка 4.x пока только beta. Standalone-вариант встраивает свой Jetty и не конфликтует с Boot BOM |
| Maven | 3.9.16 через Maven Wrapper 3.3.4 | SHA-256 дистрибутива сверен с `.sha512` на Maven Central и зафиксирован в `maven-wrapper.properties` |
| PostgreSQL (образ) | `postgres:17.11-alpine` | Стабильная ветка 17. Версию 18 не берём: в ней изменилась раскладка каталога данных в образе, а выигрыша для лабораторной нет |
| RabbitMQ (образ) | `rabbitmq:4.1.8-management-alpine` | Зрелая ветка 4.1. Ветка 4.4.0 вышла 28.10.2026, слишком свежая |
| JDK/JRE (образ) | `eclipse-temurin:21.0.13_9-jdk-alpine` / `-jre-alpine` | Актуальный патч Java 21 |
| Nginx (образ) | `nginx:1.30.6-alpine` | Stable-ветка (чётная minor) |

Правила:

- Версии, которыми управляет Spring Boot BOM, не переопределяются. Свои версии заданы только для Spring Cloud, springdoc, ArchUnit и WireMock.
- `latest`, SNAPSHOT, RC, M и beta запрещены. Для зависимостей запрет проверяет `maven-enforcer-plugin` (`requireReleaseDeps`), для образов указаны точные теги.
- Сборка и запуск подтверждены на шаге 2. Результаты каждого прогона записываются в [progress.md](progress.md).

## Архитектурные решения (ADR, кратко)

| # | Решение | Причина / компромисс |
|---|---|---|
| D1 | Шесть Spring Boot приложений в одном Git-репозитории, общий родительский POM только для версий | Удобно для лабораторной. У каждого сервиса свои `main`, JAR, Dockerfile, миграции и конфигурация |
| D2 | Модуль `fitclub-common`: только техника (JSON-логи, request id, problem+json, проверка service token, health) | Нет общих JPA-сущностей, репозиториев и доменной логики, поэтому сервисы не связаны бизнес-кодом |
| D3 | Один экземпляр PostgreSQL, шесть логических БД и шесть ролей без доступа к чужим БД | Экономит ресурсы. Изоляция данных **логическая**, сервер остаётся общей точкой отказа. Адрес БД задаётся отдельно для каждого сервиса и может указывать на другой сервер |
| D4 | Bootstrap БД отдельным одноразовым контейнером `postgres-bootstrap`, идемпотентно при каждом `up` | В отличие от `docker-entrypoint-initdb.d`, он отрабатывает и на уже существующем volume и синхронизирует пароли |
| D5 | Схемы создаёт только Flyway сервиса, `ddl-auto=validate` | Версионируемые миграции. Hibernate проверяет соответствие схемы, но не меняет её |
| D6 | Топология RabbitMQ и пользователи импортируются брокером при старте (`definitions.d`) | Очереди существуют до запуска потребителей. Приложения не объявляют топологию (`configure = "^$"`) |
| D7 | Очереди quorum с `x-delivery-limit = 5` и dead-letter в `<queue>.dlq` | Брокер ограничивает число повторных доставок даже при ошибке в коде, поэтому «горячего» бесконечного requeue не будет |
| D8 | Отдельная очередь у каждого сервиса-потребителя, routing key `<producer>.<aggregate>.<action>` | Подписчики одного события не конкурируют за общую очередь |
| D9 | Секреты в файлах `secrets/*` (вне Git), в контейнеры передаются через Compose secrets (`/run/secrets`) | Не попадают в переменные окружения образа, в `docker inspect` и в логи. Генератор написан на Java single-file, чтобы не требовать openssl/Python |
| D10 | Пользовательские JWT подписываются RS256. Закрытый ключ есть только у auth-service, остальные проверяют токен по публичному ключу локально | На каждый запрос не нужен HTTP-вызов в Auth. Уже выданный access JWT действует до `exp` (15 минут) |
| D11 | Межсервисные HTTP-вызовы защищены отдельными токенами для каждого направления в `X-Service-Token` | Учебное решение для закрытой сети контейнеров. Развитие — service JWT или mTLS (docs/security.md) |
| D12 | Порты инфраструктуры публикуются только на `127.0.0.1` и задаются переменными | Сервисы можно запускать из IDE, наружу из локальной сети ничего не открыто |
| D13 | Время хранится как `Instant`/UTC, в API передаётся в ISO 8601 со смещением, в UI показывается в `Europe/Chisinau` | Бизнес-тесты используют внедряемый `Clock` |
| D14 | Деньги — `long amount_minor` + `currency` (ISO 4217), в demo используется MDL | Никаких float/double |
| D15 | JSON-логи через встроенный structured logging Spring Boot (формат logstash + `logging.structured.json.*`), без отдельной библиотеки | Поля `timestamp`, `service`, `level`, `request_id`, `message` задаются конфигурацией. MDC очищается в `RequestIdFilter` после каждого запроса |
| D16 | Общие технические настройки — `fitclub-defaults.properties` в `fitclub-common`, подключаются `EnvironmentPostProcessor` с низшим приоритетом | Шесть сервисов не дублируют одинаковые настройки логов и health. Любое значение можно переопределить в `application.yml` сервиса |
| D17 | Собственный `ApiProblem` (record) вместо `ProblemDetail` Spring | Одинаковая сериализация в контроллерах и в фильтрах Security. Расширения `code`, `request_id`, `field_errors` всегда на верхнем уровне |
| D18 | `/health` и `/ready` — группы Actuator `liveness` и `readiness` с `additional-path`. В readiness входят `db` и `migrations` (Flyway без pending и failed) | Liveness не зависит от соседних сервисов и БД. RabbitMQ в readiness не входит (появится отдельным индикатором на шаге 5) |
| D19 | Одна стандартная цепочка Spring Security для всех сервисов (`FitClubSecurityAutoConfiguration`). Публичные пути — свойством `fitclub.security.public-paths`, роли — `@PreAuthorize`, владение — в use case'ах | Нет копий конфигурации безопасности в шести сервисах. Ошибки 401/403 выдаются в problem+json |
| D20 | Секреты читаются через `spring.config.import=configtree:${FITCLUB_SECRETS_DIR}`: в Docker это `/run/secrets`, локально `secrets/` | Одна и та же конфигурация для IDE и Compose. В контейнер попадают только секреты конкретного сервиса |
| D21 | Схемы всех таблиц из задания созданы миграциями `V1` на шаге 2. Уточнения на следующих шагах оформляются новыми версиями `V2…` | Владение данными видно сразу. Применённые миграции не редактируются |
| D22 | JPA-сущности лежат в `infrastructure.persistence`, а не в `domain`. Адаптер переводит сущность в доменную модель и обратно | Требование «domain не импортирует JPA» проверяет ArchUnit (`domainIsFrameworkFree`). Цена — небольшой код маппинга |
| D23 | `DomainException` (без Spring) для бизнес-ошибок domain и application, `ApiException` (с HttpStatus) — для технических случаев. Статус по виду ошибки выбирает `GlobalExceptionHandler` | Домен не зависит от HTTP, клиент получает стабильный `code` |
| D24 | Refresh-токены: SHA-256 вместо BCrypt, ротация с `family_id`, отзыв цепочки при повторном использовании, `SELECT … FOR UPDATE` при ротации | Токен из 256 случайных бит не подбирается, медленный хеш не нужен. Блокировка исключает двойную ротацию |
| D25 | `POST /api/auth/logout` публичный: подтверждением служит refresh-токен в теле | Выход работает и после истечения access-токена |
| D26 | Первый ADMIN создаётся `EnsureInitialAdminUseCase` при запуске из email и секрета `auth_initial_admin_password`, идемпотентно | Без этого администрировать систему некому. Пароль не хранится в репозитории и не печатается |
| D27 | `ProblemErrorController` заменяет `BasicErrorController`: `/error` тоже отвечает problem+json, `request_id` берётся из атрибута запроса | Единый формат даже для ошибок до контроллеров (файрвол Spring Security, `sendError`) |
| D28 | Тип колонок `CHAR(n)` явно указан в JPA (`@JdbcTypeCode(SqlTypes.CHAR)`) | `ddl-auto=validate` нашёл расхождение CHAR и VARCHAR при первом запуске. Схема остаётся источником истины |

## Допущения

- Авторитетный счётчик мест и учёт посещений ведёт только Booking. Schedule публикует неизменяемую `capacity`.
- После публикации занятия время, тренер и вместимость не меняются: для изменения создаётся новое занятие, старое отменяется.
- В минимальной версии нет заморозок, возвратов и изменения лимита действующего абонемента.
- Платёжный сервис — **симулятор**: данные карты не запрашиваются, деньги не списываются.
- Транспорт уведомлений — InApp, то есть сохранение в БД Notification и выдача через API.
