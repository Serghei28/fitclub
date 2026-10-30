# REST API: контракт

> Статус: исходный контракт этапа 1. Пути, DTO и коды ошибок уточняются при реализации каждого сервиса. Изменения синхронно вносятся в OpenAPI, Gateway, UI, тесты и этот файл.

## OpenAPI и Swagger UI

У каждого сервиса свои `GET /v3/api-docs` (JSON) и `/swagger-ui.html`. В dev-режиме (`docker-compose.dev.yml`) это порты 8081–8086, таблица адресов есть в README. Схемы безопасности: `bearerAuth` (JWT) для `/api/**` и `serviceToken` (`X-Service-Token`) для `/internal/**`. Доступ к документации через Gateway и UI появится на шагах 4 и 10. Документация включена в режимах local/docker/demo; для production её отключают свойством `springdoc.api-docs.enabled=false`.

## Общие правила

- Публичный префикс — `/api`, вход через Gateway. Маршруты `/internal/**` Gateway **не** публикует.
- Аутентификация: `Authorization: Bearer <access JWT>`. `user_id` берётся из проверенного JWT (`sub`), а не из тела запроса.
- Идентификаторы — UUID. Время — ISO 8601 со смещением (`2026-11-02T16:00:00Z`).
- Списки возвращаются с пагинацией: `?page=0&size=20`, `size` не больше 100. Формат ответа: `{ "items": [...], "page": 0, "size": 20, "total_elements": 42, "total_pages": 3 }`.
- Имена полей JSON — `snake_case`.
- `X-Request-ID`: принимается, если соответствует `^[A-Za-z0-9-]{8,64}$`, иначе генерируется новый. Всегда возвращается в ответе.
- `Idempotency-Key` (UUID) обязателен для `POST /api/membership-orders`, `POST /api/payments`, `POST /api/payments/{id}/simulate`, `POST /api/bookings`.

## Публичные маршруты

| Сервис | Метод и путь | Доступ | Назначение |
|---|---|---|---|
| Auth | `POST /api/auth/register` | публичный | Регистрация, всегда с ролью CLIENT |
| Auth | `POST /api/auth/login` | публичный | Вход: access + refresh |
| Auth | `POST /api/auth/refresh` | публичный (refresh в теле) | Ротация refresh, новый access |
| Auth | `POST /api/auth/logout` | авторизованный | Отзыв refresh |
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

**Маршрутизация `/api/sessions`.** В Gateway правило `Path=/api/sessions/{id}/bookings` → booking-service стоит **раньше** общего `Path=/api/sessions/**` → schedule-service. Порядок задаётся явно и проверяется тестом Gateway.

## Внутренние маршруты (service authentication)

| Получатель | Метод и путь | Вызывающий | Назначение |
|---|---|---|---|
| Auth | `GET /internal/users/{id}/trainer-status` | schedule | Активен ли пользователь и есть ли у него роль TRAINER, плюс `display_name` |
| Membership | `GET /internal/membership-orders/{id}/payment-details` | payment | Владелец, сумма, валюта, статус заказа |
| Membership | `GET /internal/memberships/eligibility?user_id=&at=` | booking | `membership_id`, результат, `[starts_at, ends_at)`, `visit_limit` |

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
