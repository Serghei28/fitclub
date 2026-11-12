# Защита: сценарий и ответы

## Подготовка (до начала, 5–7 минут)

```powershell
cd lab2
.\infrastructure\scripts\init-secrets.ps1
$env:FITCLUB_PROFILES = "docker,demo"
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
docker compose ps
docker compose --profile demo run --rm demo-seed
Get-Content secrets\demo_users_password
Get-Content secrets\auth_initial_admin_password
```

Открыть вкладки: UI http://localhost:8080, Swagger http://localhost:8000/swagger-ui.html, RabbitMQ http://localhost:15672.

## Сценарий показа (10–12 минут)

1. **Шесть сервисов.** `docker compose ps`: шесть `*-service` (healthy), отдельно gateway, frontend, postgres, rabbitmq. В Swagger справа сверху — шесть отдельных OpenAPI-документов.
2. **Границы и данные.** Схема из [architecture.md](architecture.md): у каждого сервиса своя БД и роль. Команда `docker compose exec postgres psql -U postgres -c "\l"` показывает шесть `*_db`.
3. **Клиент (UI, Ольга).** «Тарифы» → «Оформить» → тестовая оплата → «Активация абонемента…» → активирован. Пояснить: Payment закоммитил платёж и событие в outbox, relay отправил `payment.succeeded`, Membership активировал абонемент и опубликовал `membership.activated`, Notification создал уведомление.
4. **Запись.** «Расписание» → «Пилатес: индивидуально» (одно место) → «мест нет» у других. «Мои записи» → отмена → посещение вернулось.
5. **Логи и корреляция.** `docker compose logs booking-service --tail 20`: JSON с `timestamp`, `service`, `level`, `request_id`, `message`. Тот же `request_id` виден в заголовке ответа `X-Request-ID`.
6. **Права.** В Swagger клиентом `GET /api/sessions/{id}/bookings` → 403; администратор/тренер → 200. `http://localhost:8000/internal/...` → 404.
7. **Сбой (по выбору):** `docker compose stop notification-service` → запись в UI проходит, раздел «Уведомления» показывает «Сервис временно недоступен» → `docker compose start notification-service` → уведомление появилось.
8. **Тесты.** [testing.md](testing.md): итог `mvnw verify` и `run-e2e`. Показать `BookingIT.twoClientsCompeteForLastSeatExactlyOneWins` и `FailureScenariosIT`.
9. **SOLID.** [solid.md](solid.md): `MembershipEligibilityReader` (ISP), `PaymentProvider` + контрактный тест (OCP/LSP), ArchUnit-правила (DIP/SRP).

## Ответы на вопросы

### Почему Gateway, БД и UI не входят в шесть сервисов?

Бизнес-сервис владеет частью предметной области: своими данными, правилами и API. Gateway только маршрутизирует, проверяет JWT и скрывает `/internal`. PostgreSQL и RabbitMQ — хранилище и транспорт. UI — клиент API. Ни в одном из них нет бизнес-правил: в Gateway нельзя записаться на занятие, а Nginx не знает про лимит посещений. Задание прямо исключает их из подсчёта.

### Почему разделения на controller/service/repository недостаточно?

Три слоя ничего не говорят о направлении зависимостей. «Сервис», который напрямую зовёт `RestTemplate`, `RabbitTemplate` и JPA, формально разложен по слоям, но правила в нём смешаны с транспортом и не тестируются без инфраструктуры. В FitClub:
- правила — в `domain` без Spring и JPA;
- use case зависит от **своих** узких портов (`MembershipEligibilityReader`, `PaymentProvider`, `BookingEventPublisher`);
- адаптеры реализуют эти порты.

Это проверяет ArchUnit, а use case'ы тестируются с fake-адаптерами ([solid.md](solid.md)). Кроме того, границы между **сервисами** важнее слоёв внутри: Booking ничего не знает о тарифах и платежах, кроме ответа eligibility.

### Где действует локальная транзакция?

Только внутри одного сервиса и одной БД, распределённых транзакций нет. Примеры:
- **Payment, завершение оплаты:** статус платежа + строка `outbox_events` + запись `Idempotency-Key` — одна транзакция `payment_db`;
- **Membership, обработка `payment.succeeded`:** заказ → PAID, вставка абонемента, `processed_events` и событие `membership.activated` в outbox — одна транзакция `membership_db`. ACK брокеру — после commit;
- **Booking, запись:** блокировка занятия → учёт посещений → бронь, счётчики, outbox `booking.confirmed`, ключ идемпотентности — одна транзакция `booking_db`. Запрос eligibility в Membership выполняется **до** неё, чтобы сетевой вызов не держал блокировки.

Согласованность между сервисами — итоговая (eventual), через события.

### Почему outbox допускает повтор?

Relay сначала публикует событие и ждёт publisher confirm, потом отмечает `published_at`. Если процесс упадёт между этими шагами, после перезапуска событие отправится ещё раз. Это сознательный выбор at-least-once: потеря хуже повтора. Обратный порядок (сначала отметка, потом отправка) мог бы событие потерять. Повторы безопасны благодаря получателю:
- `processed_events` по `event_id` в той же транзакции, что и изменение;
- уникальные ограничения (`uq_memberships_order`, `dedup_key` уведомлений);
- версии агрегатов: устаревшее событие не откатывает более новое состояние.

### Почему лимит посещений учитывает Booking?

Решение «записать или нет» должно быть атомарным: свободное место, остаток посещений и отсутствие дубля проверяются и меняются под одной блокировкой в одной БД. Если бы счётчик вёл Membership, каждая запись требовала бы распределённой транзакции или компенсаций. При двух параллельных записях на последнее посещение без общей блокировки лимит был бы превышен. Поэтому Membership отвечает за **право** (абонемент действует в момент начала занятия, `visit_limit`), а Booking ведёт **расход** в `membership_usage` (D56). Проверено `BookingIT.oneClientTwoSessionsOneVisitLeftExactlyOneWins` и E2E `ConcurrencyIT.lastVisit`.

### Что произойдёт при остановке Membership?

- Новая запись: Booking не может проверить право → `503 DEPENDENCY_UNAVAILABLE` (или `504` при таймауте), место не занимается, записи нет. Ответа «нет абонемента» не будет: сбой не выдаётся за отказ в праве.
- Новый заказ и просмотр абонементов — 503 от Gateway. Новый платёж: Payment не может прочитать заказ → 503, платёж не создаётся.
- Уже оплаченные платежи не теряются: `payment.succeeded` ждёт в очереди `membership-service.events`, и после запуска Membership активирует абонемент.
- Расписание, отмена своей записи и уведомления продолжают работать.

Проверено: E2E `FailureScenariosIT.membershipDown`, `BookingIT.membershipOutageIsDependencyErrorNotPermission`, `PaymentApiIT.slowMembershipGivesTimeoutAndNoPayment`.

### Что произойдёт при остановке RabbitMQ?

- Пользовательские операции продолжают работать: оплата, публикация занятия и запись коммитятся локально, событие остаётся в `outbox_events` с `published_at is null`. `/ready` не зависит от брокера.
- Отложено то, что зависит от событий: активация абонемента, открытие записи на новое занятие (`SESSION_NOT_READY`), уведомления. UI показывает «Активация абонемента…» и честное сообщение по истечении ожидания.
- После запуска брокера relay повторяет отправку с нарастающей паузой (2 с → 4 с → … ≤ 5 мин). Индикатор outbox переходит в DOWN, если событие не отправлено дольше 60 с. Получатели обрабатывают события один раз: второго платежа или абонемента не появляется.

Проверено: E2E `FailureScenariosIT.brokerDown` (в `payment_db` есть неотправленное событие, после восстановления в БД ровно один платёж и один абонемент на заказ), `ScheduleApiIT.operationSucceedsWhileBrokerIsDownAndEventIsDeliveredAfterRecovery`.

### Что произойдёт при остановке Notification?

Ничего из бизнес-сценариев не ломается: Notification только потребитель событий. Запись, оплата и отмена проходят. `GET /api/notifications` → 503, UI показывает понятное сообщение, счётчик в шапке просто не обновляется. События копятся в очереди `notification-service.events`. После запуска уведомления создаются ровно один раз (`processed_events` + `dedup_key`). Задачи напоминаний лежат в `reminder_jobs` и обрабатываются после перезапуска; просроченное, но ещё не начавшееся занятие получает напоминание при ближайшей обработке.

Проверено: E2E `FailureScenariosIT.notificationDown`, `NotificationIT.reminderStoredBeforeRestartIsProcessedFromDatabase`.

### Ещё частые вопросы

- **Почему одна PostgreSQL, а не шесть серверов?** Логическая изоляция (своя БД, своя роль, нет прав на чужие БД) при меньшем расходе ресурсов учебного окружения. Сервис можно перенести на отдельный сервер, задав `FITCLUB_DB_URL` (D2, [architecture.md](architecture.md)).
- **Почему logout не отзывает access JWT сразу?** Сервисы проверяют JWT локально по публичному ключу, без запроса в Auth. Поэтому access живёт до `exp` (≤ 15 минут), а refresh отзывается сразу. Ограничение задокументировано в [security.md](security.md).
- **Как не списать оплату дважды при двойном клике?** `Idempotency-Key` в UI на одно намерение. Сервер хранит ключ, хэш тела и ответ: повтор возвращает сохранённый ответ, другое тело с тем же ключом — 409. Плюс `UNIQUE (membership_order_id)` в `payments`.
- **Почему симулятор оплаты — не «захардкоженный успех»?** Это реализация порта `PaymentProvider` с выбором исхода (SUCCESS/DECLINE). Endpoint `/simulate` существует только с профилем `demo`/`local`, а сценарий оплаты одинаков для любого провайдера (контрактный тест).
