# Тестирование

## Стратегия

| Уровень | Чем | Что проверяет | Где |
|---|---|---|---|
| Доменные правила | JUnit 5, без Spring и БД | лимиты, окна времени, переходы статусов, снимки цен, версии событий | `*DomainTest`, `SessionTest`, `PaymentTest` |
| Use case'ы | JUnit 5 + fake-адаптеры портов | сценарии и коды ошибок без инфраструктуры (DIP) | `AuthUseCasesTest`, `PlaceOrderRulesTest`, `PaymentRulesTest`, `ScheduleCommandServiceTest`, `NotificationScenariosTest` |
| Контракт порта | абстрактный тест на две реализации | взаимозаменяемость провайдеров оплаты (LSP) | `PaymentProviderContractTest` |
| Архитектура | ArchUnit | слои, чистый домен, контроллеры без репозиториев, нет импортов чужих сервисов | `ArchitectureTest` × 6, `GatewayArchitectureTest` |
| Интеграция сервиса | Spring Boot + **настоящие PostgreSQL и RabbitMQ (Testcontainers)**, WireMock для соседнего сервиса | API, миграции, блокировки, outbox, потребители, DLQ, конкурентность | `*ApiIT`, `BookingIT`, `PaymentEventsIT`, `NotificationIT`, `GatewayIT`, `*SkeletonIT` |
| End-to-end | JUnit 5 + Awaitility, HTTP через Gateway на Docker Compose стенде | полный сценарий, права, конкурентная запись, остановка Notification/Membership/RabbitMQ | `tests/e2e` |

Принципы:
- мок транспорта не выдаётся за сквозную проверку. WireMock используется только там, где проверяется HTTP-клиент одного сервиса (таймаут, 5xx, служебный токен). Хранение и сообщения проверяются на настоящих PostgreSQL и RabbitMQ;
- конкурентные тесты отправляют действительно параллельные HTTP-запросы (общий старт через `CountDownLatch`) к одной БД и проверяют итоговые инварианты в данных;
- асинхронность ожидается через Awaitility с ограниченным сроком и описанием ожидания, а не через длинный `sleep`;
- время в правилах — внедряемый `Clock`. Напоминание «за 60 минут» и граница окна отмены проверяются фиксированными часами;
- E2E работает на отдельном Compose-проекте `fitclub-e2e` (свои контейнеры, volumes и порты). Каждый запуск создаёт уникальных пользователей, тарифы и занятия, поэтому данные основного стенда не меняются.

## Команды

Все команды выполняются из каталога `lab2`. Нужен запущенный Docker Desktop: Testcontainers поднимает PostgreSQL и RabbitMQ сам.

Модульные, архитектурные и интеграционные тесты всех модулей:

```powershell
.\mvnw.cmd verify
```

```bash
./mvnw verify
```

Один сервис (например, booking) вместе с общими модулями:

```powershell
.\mvnw.cmd -pl services/booking-service -am verify
```

Сквозные проверки. Скрипт поднимает изолированный стенд `fitclub-e2e` с профилем `docker,demo`, запускает `tests/e2e` и затем останавливает стенд без удаления данных:

```powershell
.\tests\e2e\run-e2e.ps1
```

```bash
sh tests/e2e/run-e2e.sh
```

С ключом `-KeepRunning` (`--keep-running`) стенд остаётся запущенным. После этого E2E можно повторять без пересборки:

```powershell
.\mvnw.cmd -Pe2e -pl tests/e2e verify
```

Удалить данные **только** изолированного E2E-стенда (основной стенд `fitclub` не затрагивается):

```bash
docker compose -p fitclub-e2e down -v
```

## Обязательные группы проверок

| Область | Проверки (класс → тест) |
|---|---|
| Auth | `AuthApiIT`: регистрация даёт только CLIENT и игнорирует запрошенные роли; email уникален без учёта регистра, в том числе при параллельной регистрации; неверный пароль и неизвестный email дают одинаковый ответ; поддельный токен; ротация refresh и отзыв цепочки при повторе; logout; хранение только хэшей; admin не снимает с себя ADMIN. `CommonWebSecurityTest`: истёкший, чужой ключ, issuer, audience, HS256 |
| Authorization | `BookingIT.foreignBookingCannotBeCancelledOrSeen`, `onlyAssignedTrainerAndAdminSeeParticipants`; `PaymentRulesTest.onlyOwnerCanCompletePayment`, `foreignOrderLooksLikeMissingOrder`; `NotificationIT.onlyRecipientCanMarkAsRead`; `CommonWebSecurityTest`: нет или неверный `X-Service-Token`, пользовательский JWT не открывает `/internal`, вызывающий видит только свои маршруты. E2E `AccessControlIT` |
| Schedule | `ScheduleApiIT`: некорректный период и вместимость; назначение не-тренера; снимок тренера из Auth; опубликованное занятие не редактируется; публикация и отмена пишут outbox и доставляются в очередь Booking; сбой Auth — ошибка зависимости, а не ложный успех |
| Membership | `PaymentEventsIT`: успех активирует ровно один абонемент с началом в момент оплаты; несовпадение суммы не применяется и уходит в DLQ; отказ не активирует, разрешает новый заказ, опоздавший отказ игнорируется; отказ после успеха не отзывает абонемент. `MembershipDomainTest.membershipIsValidOnHalfOpenInterval`, `MembershipApiIT.eligibilityRespectsHalfOpenPeriod` — граница срока |
| Booking | `BookingIT`: нет абонемента или он не действует на дату занятия; полная группа и исчерпанный лимит; повторная запись; начавшееся занятие; точная граница окна отмены и однократный возврат; старый `Idempotency-Key` после отмены; «надгробие» отмены, пришедшей раньше публикации |
| Конкурентность | `BookingIT.twoClientsCompeteForLastSeatExactlyOneWins`, `tenClientsForThreeSeats`, `oneClientTwoSessionsOneVisitLeftExactlyOneWins`, `userCancellationRacingSessionCancellationRefundsOnce`; `MembershipApiIT.parallelOrdersOfOneUser…`, `parallelRetriesWithSameKeyExecuteOnce`; `PaymentApiIT.oneOrderHasAtMostOnePaymentEvenWithDifferentKeysInParallel`; `AuthApiIT.concurrentRegistration…`. E2E `ConcurrencyIT` через Gateway |
| Payment | `PaymentApiIT`: сумма берётся из Membership, цена клиента игнорируется; повтор с тем же ключом и конфликт при другом payload; один заказ — один платёж; чужой заказ — 404; медленный Membership — таймаут без платежа; событие успеха публикуется один раз |
| События | `PaymentEventsIT.malformedMessageGoesToDlqWithoutBlockingQueue`; `ScheduleApiIT.operationSucceedsWhileBrokerIsDownAndEventIsDeliveredAfterRecovery`, `unroutableEventStaysPendingInsteadOfBeingLost`; `NotificationIT.…Deduplicated` (повтор того же и нового `event_id`), `lateConfirmationAfterCancellationCreatesNothing`; `BookingIT.sessionCancellationReleasesEverythingAndStalePublishCannotReopen`. E2E `FailureScenariosIT` |
| Notification | `NotificationIT`: уведомление доступно через API без дублей; напоминание ровно один раз в `start − 60 мин` при управляемом `Clock`; отмена подавляет напоминание; задача из БД обрабатывается после перезапуска |
| Надёжность API | `GatewayIT.unavailableServiceGivesControlled503`, `slowServiceGivesControlled504`, `gatewayHealthDoesNotDependOnServices`; `DatabaseOutageIT.readinessFailsWhileLivenessStaysUpWhenDatabaseIsLost`; `CommonWebSecurityTest.unexpectedErrorHidesInternalDetails`, `databaseFailureIsServiceUnavailable`, `containerErrorPageUsesSameProblemFormat`; `BookingIT.membershipOutageIsDependencyErrorNotPermission` |
| Архитектура | `ArchitectureTest` каждого сервиса (4 правила), `GatewayArchitectureTest`; подробно — в [solid.md](solid.md) |

## Сквозные проверки (`tests/e2e`)

| Класс | Сценарий |
|---|---|
| `FullJourneyIT` | Вход ADMIN и клиента → тариф и опубликованное занятие → заказ (повтор ключа — тот же заказ) → платёж (сумма из заказа) → тестовое успешное завершение → активный абонемент (ожидание события) → запись: свободных мест и остаток посещений −1 → подтверждение и активация в Notification API → повтор исходных запросов оплаты: тот же платёж, второй активации нет, срок не продлён, одно уведомление об активации; противоположный исход → 409 → отмена: место и посещение возвращаются один раз, повторная отмена ничего не меняет, повтор исходной записи возвращает сохранённый ответ → уведомление об отмене |
| `AccessControlIT` | Без токена и с поддельным — 401 problem+json с `X-Request-ID`; `/internal/**` снаружи — 404; клиент не назначает себе ADMIN и не создаёт тарифы; чужие платёж, заказ, запись и уведомление — 404; участников видит только назначенный тренер |
| `ConcurrencyIT` | Пять клиентов одновременно на последнее место → ровно одна запись, остальные `SESSION_FULL`; одно оставшееся посещение и два занятия одновременно → одна запись, вторая `VISIT_LIMIT_REACHED` |
| `FailureScenariosIT` | **Notification остановлен:** запись проходит (201), `/api/notifications` → 503; после запуска уведомление из очереди появляется ровно один раз. **Membership остановлен:** запись → 503 `DEPENDENCY_*`, заказ → 503, место не занято, записи нет; после запуска запись проходит. **RabbitMQ остановлен:** тестовая оплата → 200 `SUCCEEDED`, событие ждёт в `outbox_events` (`published_at is null`), абонемента нет, повтор запроса — тот же результат; после запуска брокера relay отправляет событие, появляется один абонемент; в БД один платёж и один абонемент на заказ |

## Фактические результаты

Выполнено 12.11.2026 на Windows 11, Docker Desktop (8 ГБ, Linux-контейнеры), JDK 21.

**`.\mvnw.cmd verify` — BUILD SUCCESS, 270 тестов, 0 падений, 0 пропущено (3 мин 25 с):**

| Модуль | Тестов |
|---|---|
| common (`fitclub-common`) | 28 |
| auth-service | 52 |
| schedule-service | 45 |
| membership-service | 40 |
| booking-service | 32 |
| payment-service | 31 |
| notification-service | 25 |
| gateway | 17 |
| **Итого** | **270** |

**`.\tests\e2e\run-e2e.ps1` — BUILD SUCCESS, 9 сквозных тестов, 0 падений (тесты 1 мин 13 с, без учёта подъёма стенда):**

| Класс | Тестов | Время |
|---|---|---|
| `AccessControlIT` | 3 | 6,1 с |
| `ConcurrencyIT` | 2 | 9,2 с |
| `FailureScenariosIT` | 3 | 50,4 с |
| `FullJourneyIT` | 1 | 4,7 с |

Первый прогон E2E упал в двух местах из-за ошибок в самих тестах: устаревший токен тренера до повторного входа и неверное ожидание 409 для повторного успешного завершения оплаты. Третий прогон нашёл дефект с точностью времени (см. ниже). После исправлений все проверки проходят.

## Найдено тестами

- **Шаг 8.** Hibernate при `FOR UPDATE` не обновлял уже загруженную сущность. Нашёл конкурентный тест двойного `BookingCancelled`; исправлено перечитыванием с блокировкой (D54).
- **Шаг 11 (E2E).** Ответ на завершение оплаты содержал `completed_at` с наносекундами, а последующее чтение из БД — с микросекундами: один момент API показывал по-разному. Общий `Clock` переведён на шаг 1 мкс (точность `TIMESTAMPTZ`).
- **Шаг 11 (E2E).** Healthcheck `frontend` обращался к `localhost`, который в alpine резолвится в IPv6, а Nginx слушает IPv4. Контейнер был `unhealthy`, и `docker compose up --wait` падал. Исправлено на `127.0.0.1`.

## Что не автоматизировано

- Экраны UI проверены вручную во встроенном браузере (шаг 10: клиентский сценарий целиком). Браузерных автотестов (Playwright/Selenium) нет: UI — тонкий клиент к уже проверенному API.
- Нагрузочного тестирования нет. Конкурентность проверяется на корректность (инварианты), а не на производительность.
