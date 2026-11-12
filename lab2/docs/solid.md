# SOLID в FitClub

SOLID проверяется по коду и тестам, а не по названиям папок. Внутри каждого сервиса четыре слоя:

| Слой | Что в нём | Пример (booking-service) |
|---|---|---|
| `api` | HTTP, DTO, коды ответов, OpenAPI | `BookingController` |
| `application` | use case'ы (координация сценария, транзакция) и **их порты** | `CreateBookingUseCase`, `CancelBookingUseCase`, `port/BookingPorts` |
| `domain` | бизнес-правила без Spring/JPA/AMQP | `Booking`, `BookingPolicy`, `MembershipUsage`, `SessionInventory` |
| `infrastructure` | адаптеры: JPA, HTTP-клиенты, outbox, RabbitMQ, безопасность | `JpaBookingRepository`, `MembershipEligibilityClient`, `OutboxBookingEventPublisher` |

Направление зависимостей проверяют общие правила ArchUnit ([ArchitectureRules.java](../libs/fitclub-test-support/src/main/java/com/fitclub/testsupport/ArchitectureRules.java)). Их запускает `ArchitectureTest` каждого сервиса и `GatewayArchitectureTest`:

| Правило | Что запрещает |
|---|---|
| `layers` | `application` не обращается к `api` и `infrastructure`; к `infrastructure` и `api` не обращается никто |
| `domainIsFrameworkFree` | `domain` не импортирует Spring Web/HTTP, Servlet, AMQP, JPA/Hibernate, Spring Data, JDBC, Jackson |
| `controllersUseApplicationOnly` | `api` не обращается к репозиториям, JDBC, AMQP и `infrastructure` |
| `noDependencyOnOtherServices` | ни один сервис не импортирует классы другого сервиса: только HTTP и RabbitMQ |

## S — Single Responsibility

**Где.** Сценарий записи на занятие разложен по ответственностям:

- [BookingController](../services/booking-service/src/main/java/com/fitclub/booking/api/BookingController.java) — разбор HTTP, `Idempotency-Key`, DTO и код ответа (201/200). В нём нет SQL, расчёта лимита и публикации событий;
- [CreateBookingUseCase](../services/booking-service/src/main/java/com/fitclub/booking/application/CreateBookingUseCase.java) — порядок шагов: запросить eligibility вне транзакции, затем заблокировать занятие → учёт посещений → бронь, применить правила, сохранить, записать событие в outbox;
- [BookingPolicy](../services/booking-service/src/main/java/com/fitclub/booking/domain/BookingPolicy.java), [MembershipUsage](../services/booking-service/src/main/java/com/fitclub/booking/domain/MembershipUsage.java), [SessionInventory](../services/booking-service/src/main/java/com/fitclub/booking/domain/SessionInventory.java) — правила: можно ли записаться, лимит посещений, места, окно отмены;
- `JpaBookingRepository`, `JpaMembershipUsageRepository` — хранение и блокировки; `MembershipEligibilityClient` — HTTP-транспорт к Membership; `OutboxBookingEventPublisher` — формат события.

Так же устроены остальные сервисы. Например, в payment-service `PaymentController` → `CompletePaymentUseCase` → `Payment` (домен) → `SimulatorPaymentProvider` / `OutboxPaymentEventPublisher`. Техника, общая для всех (JSON-логи, `X-Request-ID`, problem+json, JWT, outbox relay), вынесена в `fitclub-common` и `fitclub-messaging`, бизнес-правил там нет.

Огромного `FitClubService` и общего `BaseService` нет. Read- и write-сценарии разделены: `BookingQueryService` и `CreateBookingUseCase`/`CancelBookingUseCase`.

**Проверка.**
- `controllersUseApplicationOnly` и `domainIsFrameworkFree` в каждом `ArchitectureTest`.
- Правила проверяются без веб-сервера и БД: `BookingDomainTest`, `MembershipDomainTest`, `SessionTest`, `PaymentTest`, `NotificationDomainTest`, `AuthDomainTest`.

## O — Open/Closed

**Где.**
- Оплата идёт через порт [PaymentProvider](../services/payment-service/src/main/java/com/fitclub/payment/application/port/PaymentProvider.java). Реализация в проекте — [SimulatorPaymentProvider](../services/payment-service/src/main/java/com/fitclub/payment/infrastructure/provider/SimulatorPaymentProvider.java). Настоящий провайдер добавляется новым адаптером, `CreatePaymentUseCase` и `CompletePaymentUseCase` при этом не меняются. `switch` по провайдерам в бизнес-правилах нет.
- Доставка уведомлений идёт через порт `NotificationChannel` ([NotificationPorts](../services/notification-service/src/main/java/com/fitclub/notification/application/port/NotificationPorts.java)), реализация — `InAppNotificationChannel`. Email или push — новый адаптер, `NotificationEventHandler` и `ReminderProcessor` не меняются.
- Обработка событий: [EventProcessor](../libs/fitclub-messaging/src/main/java/com/fitclub/messaging/EventProcessor.java) (дедупликация `processed_events`, ACK после commit, retry/DLQ) один для всех. Новый потребитель — новая реализация `EventHandler`, сам процессор не меняется.

**Проверка.**
- `InMemoryGatewayProviderContractTest` подставляет второй провайдер в тот же контракт.
- `NotificationScenariosTest` проверяет сценарии уведомлений с fake-каналом.
- `PaymentRulesTest` проверяет use case'ы оплаты с тестовыми адаптерами, без переписывания сценария.

## L — Liskov Substitution

**Где.** Контракт `PaymentProvider` записан в javadoc порта:
- `register` выдаёт уникальную непустую ссылку;
- `finish` для известной ссылки всегда возвращает `approved` или `declined` с причиной;
- неизвестная ссылка — `IllegalArgumentException`, а не `UnsupportedOperationException`;
- сбой связи — ошибка 502/503/504, а не «успех».

Use case не проверяет конкретный класс провайдера.

Контракт `NotificationChannel`: повтор того же факта (`dedup_key`) не создаёт дубль и возвращает `false`. Контракт `EventHandler`: необратимая ошибка — `NonRetryableEventException` (сообщение в DLQ), остальные — повтор.

**Проверка.** Абстрактный [PaymentProviderContractTest](../services/payment-service/src/test/java/com/fitclub/payment/infrastructure/PaymentProviderContractTest.java) выполняется для двух реализаций: `SimulatorProviderContractTest` и `InMemoryGatewayProviderContractTest`. Три одинаковые проверки: уникальная ссылка, исход всегда определён, неизвестная ссылка — `IllegalArgumentException`.

## I — Interface Segregation

**Где.** Порты узкие и принадлежат потребителю:

| Потребитель | Порт | Что умеет | Чего не умеет |
|---|---|---|---|
| Booking | `MembershipEligibilityReader` | `eligibility(userId, at)` — есть ли право на посещение в момент начала занятия | менять тарифы, заказы, проводить оплату |
| Payment | `MembershipOrderReader` | `paymentDetails(orderId)` — сумма, валюта, владелец, статус заказа | менять заказы и абонементы |
| Schedule | `TrainerDirectory` | статус тренера (активен, роль TRAINER, имя) | управлять пользователями |
| use case'ы | `*EventPublisher` по сервису (`BookingEventPublisher`, `PaymentEventPublisher`, …) | только свои события | публиковать чужие события |

Репозитории тоже разделены по агрегатам: `BookingRepository`, `MembershipUsageRepository`, `SessionInventoryRepository` вместо одного «BookingDao» на всё.

На уровне сети узость повторяют внутренние API: `/internal/memberships/eligibility`, `/internal/membership-orders/{id}/payment-details`, `/internal/users/{id}/trainer-status`. Служебный токен вызывающего открывает только его маршруты.

**Проверка.**
- `CommonWebSecurityTest.callerReachesOnlyItsOwnRoutes`, `MembershipApiIT.paymentDetailsOnlyForPaymentService`.
- `noDependencyOnOtherServices`: Booking физически не может вызвать код Membership.

## D — Dependency Inversion

**Где.** Use case'ы зависят от портов, объявленных в их же `application.port`, а адаптеры из `infrastructure` эти порты реализуют:

| Порт (application) | Адаптер (infrastructure) |
|---|---|
| `BookingRepository`, `MembershipUsageRepository`, `SessionInventoryRepository` | `JpaBookingRepository`, `JpaMembershipUsageRepository`, `JpaSessionInventoryRepository` |
| `MembershipEligibilityReader` | `MembershipEligibilityClient` (HTTP + `X-Service-Token`) |
| `BookingEventPublisher` | `OutboxBookingEventPublisher` |
| `MembershipOrderReader` / `PaymentProvider` | `MembershipOrderClient` / `SimulatorPaymentProvider` |
| `TrainerDirectory` | `AuthTrainerDirectory` |
| `PasswordHasher`, `AccessTokenIssuer`, `UserRepository` | `SpringPasswordHasher`, `JwtAccessTokenIssuer`, `JpaUserRepository` |
| `NotificationChannel`, `ReminderRepository` | `InAppNotificationChannel`, `JpaReminderRepository` |

Все зависимости внедряются через конструктор, время — через `Clock`. Создание доменных объектов через `new` (например, `new Booking(...)`) не нарушает DIP: от инфраструктуры зависимость не появляется.

**Проверка.**
- `layers` и `domainIsFrameworkFree` в каждом `ArchitectureTest`.
- Use case'ы тестируются с fake-адаптерами без Spring и БД: `AuthUseCasesTest` ([FakeAdapters](../services/auth-service/src/test/java/com/fitclub/auth/application/FakeAdapters.java)), `PlaceOrderRulesTest`, `PaymentRulesTest`, `ScheduleCommandServiceTest`, `NotificationScenariosTest`.
- Время управляется фиксированным `Clock`: напоминание за 60 минут и граница окна отмены проверяются без ожидания реального времени.

## Чего сознательно нет

- Интерфейсов для DTO и чистых расчётов. `BookingPolicy` — конкретный класс: другой реализации у правила нет.
- Наследования ради повторного использования. Общий код подключается композицией (`IdempotencyStore`, `OutboxWriter`, `InternalServiceClient`).
- Универсального репозитория на десятки методов. В каждом порте только то, что нужно его use case'ам.
