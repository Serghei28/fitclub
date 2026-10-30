# REST API: контракт

> Статус: исходный контракт этапа 1. Пути, DTO и коды ошибок уточняются при реализации каждого сервиса. Изменения синхронно вносятся в OpenAPI, Gateway, UI, тесты и этот файл.

## OpenAPI и Swagger UI

У каждого сервиса свои `GET /v3/api-docs` (JSON) и `/swagger-ui.html`. В dev-режиме (`docker-compose.dev.yml`) это порты 8081–8086, таблица адресов есть в README. Схемы безопасности: `bearerAuth` (JWT) для `/api/**` и `serviceToken` (`X-Service-Token`) для `/internal/**`. Через Gateway все шесть документов доступны в одном Swagger UI: http://localhost:8000/swagger-ui.html. Запросы «Try it out» оттуда идут через Gateway. Документация включена в режимах local/docker/demo; для production её отключают свойством `springdoc.api-docs.enabled=false`.

## Общие правила

- Публичный префикс — `/api`, вход через Gateway. Маршруты `/internal/**` Gateway **не** публикует.
- Аутентификация: `Authorization: Bearer <access JWT>`. `user_id` берётся из проверенного JWT (`sub`), а не из тела запроса.
- Идентификаторы — UUID. Время — ISO 8601 со смещением (`2026-11-02T16:00:00Z`).
- Списки возвращаются с пагинацией: `?page=0&size=20`, `size` не больше 100. Формат ответа: `{ "items": [...], "page": 0, "size": 20, "total_elements": 42, "total_pages": 3 }`.
- Имена полей JSON — `snake_case`.
- `X-Request-ID`: принимается, если соответствует `^[A-Za-z0-9-]{8,64}$`, иначе генерируется новый. Всегда возвращается в ответе.
- `Idempotency-Key` (UUID) обязателен для `POST /api/membership-orders`, `POST /api/payments`, `POST /api/payments/{id}/simulate`, `POST /api/bookings`.

## API Gateway (шаг 4)

Единая точка входа: `http://localhost:8000` (в Compose порт задаётся переменной `GATEWAY_HOST_PORT`). Общий Swagger UI со всеми сервисами: http://localhost:8000/swagger-ui.html, сервис выбирается в выпадающем списке.

| Порядок | Маршрут | Сервис |
|---|---|---|
| 0 | `/api/sessions/{id}/bookings` | booking-service (участники занятия) |
| 10 | `/api/auth/**`, `/api/admin/users/**` | auth-service |
| 20 | `/api/training-types/**`, `/api/sessions`, `/api/sessions/**` | schedule-service |
| 20 | `/api/plans/**`, `/api/membership-orders/**`, `/api/memberships/**` | membership-service |
| 20 | `/api/payments/**` | payment-service |
| 20 | `/api/bookings/**`, `/api/availability` | booking-service |
| 20 | `/api/notifications/**` | notification-service |
| 30 | `/docs/{service}/v3/api-docs` → `/v3/api-docs` сервиса | OpenAPI для общего Swagger UI |
| — | `/internal/**` | **не публикуется**, ответ `404` без обращения к сервисам |

Пути не переписываются: сервис получает тот же `/api/...`. Маршрут участников занятия стоит раньше общего `/api/sessions/**`, это проверяет тест `participantsOfSessionGoToBookingButSessionGoesToSchedule`.

Что делает Gateway:
- проверяет JWT (RS256, `iss`, `aud`, `exp`, `sub`) на всех `/api/**`, кроме `POST /api/auth/register|login|refresh|logout`. Недействительный токен отсекается здесь, но роли и владение ресурсами проверяет сам сервис;
- удаляет из входящих запросов `X-Service-Token`;
- отклоняет пути с `..`, `//`, `;`, `\` и их URL-кодированными формами (`400`);
- принимает или создаёт `X-Request-ID`, передаёт его сервису и возвращает клиенту;
- передаёт сервисам `X-Forwarded-Host/Proto/Port`. Gateway доверяет только loopback и частным сетям (`trusted-proxies`).

Чего Gateway не делает: не активирует абонементы, не считает лимиты, не хранит данные. Проверяет `GatewayArchitectureTest`.

### Ошибки зависимостей

| Ситуация | Ответ | Где формируется |
|---|---|---|
| Сервис не принимает соединение, остановлен или имя не резолвится | `503 DEPENDENCY_UNAVAILABLE` | Gateway |
| Сервис не ответил за `response-timeout` (10 с, `FITCLUB_GATEWAY_RESPONSE_TIMEOUT`) | `504 DEPENDENCY_TIMEOUT` | Gateway |
| Соединение оборвано во время ответа | `502 DEPENDENCY_BAD_RESPONSE` | Gateway |
| Сервис ответил ошибкой (4xx/5xx problem+json) | **без изменений** | сервис |
| Нет маршрута | `404 RESOURCE_NOT_FOUND` | Gateway |

Таймаут соединения — 2 с (`FITCLUB_GATEWAY_CONNECT_TIMEOUT_MS`). Gateway не повторяет запросы сам: произвольный POST вслепую не повторяется. Адреса сервисов в тело ответа не попадают.

## Публичные маршруты

| Сервис | Метод и путь | Доступ | Назначение |
|---|---|---|---|
| Auth | `POST /api/auth/register` | публичный | Регистрация, всегда с ролью CLIENT |
| Auth | `POST /api/auth/login` | публичный | Вход: access + refresh |
| Auth | `POST /api/auth/refresh` | публичный (refresh в теле) | Ротация refresh, новый access |
| Auth | `POST /api/auth/logout` | публичный (refresh в теле) | Отзыв цепочки refresh-токенов, идемпотентно, 204 |
| Auth | `GET /api/auth/me` | авторизованный | Текущий профиль |
| Auth | `GET /api/admin/users?role=&q=&page=&size=` | ADMIN | Список/поиск пользователей |
| Auth | `PATCH /api/admin/users/{id}/roles` | ADMIN | Назначение ролей |
| Schedule | `GET /api/training-types` | авторизованный | Типы занятий |
| Schedule | `POST /api/training-types` | ADMIN | Создание типа |
| Schedule | `GET /api/sessions?from=&to=&trainer_id=&status=` | авторизованный (DRAFT видит только ADMIN) | Расписание |
| Schedule | `GET /api/sessions/mine` | TRAINER | Собственные занятия тренера |
| Schedule | `GET /api/sessions/{id}` | авторизованный | Карточка занятия |
| Schedule | `POST /api/sessions` | ADMIN | Создание DRAFT |
| Schedule | `PATCH /api/sessions/{id}` | ADMIN | Изменение, только DRAFT |
| Schedule | `POST /api/sessions/{id}/publish` | ADMIN | Публикация |
| Schedule | `POST /api/sessions/{id}/cancel` | ADMIN | Отмена |
| Membership | `GET /api/plans` | авторизованный (неактивные видит только ADMIN) | Тарифы |
| Membership | `POST /api/plans` | ADMIN | Создание тарифа |
| Membership | `PATCH /api/plans/{id}` | ADMIN | Изменение/деактивация |
| Membership | `POST /api/membership-orders` | владелец | Заказ по `plan_id` (или возврат существующего ожидающего) |
| Membership | `GET /api/membership-orders/{id}` | владелец | Статус заказа |
| Membership | `GET /api/memberships/me` | владелец | Свои абонементы и эффективный срок |
| Payment | `POST /api/payments` | владелец заказа | Платёж по `membership_order_id` |
| Payment | `GET /api/payments/{id}` | владелец | Состояние платежа |
| Payment | `POST /api/payments/{id}/simulate` | владелец; только профили local/demo/test | Тестовое завершение `SUCCESS` / `DECLINE` |
| Booking | `GET /api/availability?session_id=..&session_id=..` | авторизованный | Свободные места |
| Booking | `POST /api/bookings` | владелец | Запись на занятие |
| Booking | `GET /api/bookings/me` | владелец | Записи и история |
| Booking | `GET /api/bookings/usage/me` | владелец | Использовано/осталось посещений |
| Booking | `POST /api/bookings/{id}/cancel` | владелец | Отмена своей записи |
| Booking | `GET /api/sessions/{id}/bookings` | назначенный TRAINER или ADMIN | Участники занятия |
| Notification | `GET /api/notifications?unread=` | владелец | Уведомления |
| Notification | `GET /api/notifications/unread-count` | владелец | Количество непрочитанных |
| Notification | `PATCH /api/notifications/{id}/read` | получатель | Отметка прочтения |

`GET /api/sessions/mine` и `GET /api/notifications/unread-count` уточняют исходный контракт: в задании эти сценарии описаны («список собственных занятий тренера», «количество непрочитанных»), но отдельные пути не названы.

### Auth: реализовано (шаг 3)

Регистрация (`201`, всегда роль `CLIENT`):

```http
POST /api/auth/register
Content-Type: application/json

{"email": "anna@example.com", "password": "S3cure-pass", "display_name": "Анна Петрова"}
```

```json
{"id": "8c1e…", "email": "anna@example.com", "display_name": "Анна Петрова", "roles": ["CLIENT"],
 "active": true, "created_at": "2026-10-30T16:00:00Z"}
```

Вход и refresh возвращают один формат (`Cache-Control: no-store`):

```json
{"token_type": "Bearer", "access_token": "eyJ…", "expires_in": 900,
 "access_token_expires_at": "2026-10-30T16:15:00Z",
 "refresh_token": "q3T…", "refresh_token_expires_at": "2026-11-06T16:00:00Z",
 "user": {"id": "8c1e…", "email": "anna@example.com", "display_name": "Анна Петрова", "roles": ["CLIENT"], "active": true, "created_at": "…"}}
```

`POST /api/auth/refresh` и `POST /api/auth/logout` принимают `{"refresh_token": "…"}`.

| Ситуация | Ответ |
|---|---|
| Email уже занят (без учёта регистра и пробелов) | `409 EMAIL_ALREADY_USED`, `field_errors[0].field = email` |
| Пароль короче 8 символов / длиннее 72 байт UTF-8 | `400 WEAK_PASSWORD` / `400 PASSWORD_TOO_LONG` |
| Неверный email или пароль | `401 INVALID_CREDENTIALS` (одинаково для обоих случаев) |
| Refresh истёк, отозван или использован повторно | `401 INVALID_REFRESH_TOKEN` |
| Не ADMIN вызывает `/api/admin/**` | `403 ACCESS_DENIED` |
| ADMIN снимает ADMIN с себя | `409 CANNOT_REVOKE_OWN_ADMIN` |
| `size` > 100 | `400 VALIDATION_ERROR` |

`GET /api/admin/users?role=TRAINER&q=ольга&page=0&size=20` сортирует по email и ищет подстроку в email или имени без учёта регистра. `PATCH /api/admin/users/{id}/roles` с телом `{"roles": ["CLIENT", "TRAINER"]}` заменяет набор ролей целиком.

Внутренний `GET /internal/users/{id}/trainer-status` (только `X-Service-Token` schedule → auth):

```json
{"user_id": "f19f…", "active": true, "trainer": true, "display_name": "Ольга Тренер"}
```

Для неизвестного пользователя — `404 RESOURCE_NOT_FOUND`.

**Маршрутизация `/api/sessions`.** В Gateway правило `Path=/api/sessions/{id}/bookings` → booking-service стоит **раньше** общего `Path=/api/sessions/**` → schedule-service. Порядок задаётся явно и проверяется тестом Gateway.

## Внутренние маршруты (service authentication)

| Получатель | Метод и путь | Вызывающий | Назначение |
|---|---|---|---|
| Auth | `GET /internal/users/{id}/trainer-status` | schedule | Активен ли пользователь и есть ли у него роль TRAINER, плюс `display_name` |
| Membership | `GET /internal/membership-orders/{id}/payment-details` | payment | Владелец, сумма, валюта, статус заказа |
| Membership | `GET /internal/memberships/eligibility?user_id=&at=` | booking | `membership_id`, результат, `[starts_at, ends_at)`, `visit_limit` |

Ошибки, возникающие до контроллеров (например, отказ файрвола Spring Security для пути с `//`), отдаёт `ProblemErrorController` в том же формате problem+json.

Каждый внутренний endpoint принимает только токен своего вызывающего (`X-Service-Token`, сравнение за постоянное время) и отклоняет остальные с `401`/`403` в формате problem+json.

## Формат ошибок (`application/problem+json`)

```json
{
  "type": "https://fitclub.local/problems/session-full",
  "title": "Мест нет",
  "status": 409,
  "detail": "На занятие больше нет свободных мест.",
  "instance": "/api/bookings",
  "code": "SESSION_FULL",
  "request_id": "3f6c2a9e-1b7d-4c55-9a0e-6d2b8f1e4a77"
}
```

При ошибке валидации добавляется `field_errors: [{ "field": "email", "message": "..." }]`. Stack trace, SQL, внутренние адреса и секреты в теле не появляются никогда.

| HTTP | Когда | Примеры `code` |
|---|---|---|
| 400 | Некорректный запрос, валидация | `VALIDATION_ERROR`, `MALFORMED_REQUEST` |
| 401 | Нет/недействителен токен | `AUTHENTICATION_REQUIRED`, `INVALID_TOKEN`, `INVALID_CREDENTIALS` |
| 403 | Действие запрещено | `ACCESS_DENIED` |
| 404 | Ресурс не найден или чужой (чтобы не раскрывать существование) | `RESOURCE_NOT_FOUND` |
| 409 | Конфликт состояния | `EMAIL_ALREADY_USED`, `SESSION_FULL`, `VISIT_LIMIT_REACHED`, `MEMBERSHIP_NOT_VALID`, `SESSION_NOT_READY`, `ALREADY_BOOKED`, `CANCELLATION_WINDOW_CLOSED`, `INVALID_STATE_TRANSITION`, `IDEMPOTENCY_CONFLICT`, `PENDING_ORDER_EXISTS` |
| 502 | Зависимость вернула некорректный/ошибочный ответ | `DEPENDENCY_BAD_RESPONSE` |
| 503 | Зависимость или своя БД недоступна | `DEPENDENCY_UNAVAILABLE`, `DATABASE_UNAVAILABLE` |
| 504 | Зависимость не ответила в timeout | `DEPENDENCY_TIMEOUT` |
| 500 | Непредвиденная ошибка | `INTERNAL_ERROR` |
