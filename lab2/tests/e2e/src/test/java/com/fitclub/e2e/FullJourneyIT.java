package com.fitclub.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import com.fitclub.e2e.support.Api.Response;
import com.fitclub.e2e.support.Fitclub;
import com.fitclub.e2e.support.Fitclub.User;

/**
 * Полный пользовательский сценарий через Gateway: REST между сервисами и события RabbitMQ
 * (payment.succeeded → membership.activated, session.published, booking.confirmed/cancelled).
 */
class FullJourneyIT {

    private final Fitclub fc = new Fitclub();

    @Test
    @DisplayName("Абонемент → оплата → запись → уведомление → повтор оплаты без второй активации → отмена с однократным возвратом")
    void fullJourney() {
        // 1. Администратор и клиент входят; администратор создаёт тариф и публикует будущее занятие
        String admin = fc.admin();
        assertThat(fc.api.get("/api/auth/me", admin).body().path("roles").toString()).contains("ADMIN");
        User trainer = fc.trainer("trainer");
        User client = fc.client("client");
        UUID planId = fc.plan("E2E 2 посещения", 2, 20000);
        UUID sessionId = fc.publishedSession(trainer, "E2E йога", 48, 5);

        // 2. Заказ абонемента; повтор с тем же Idempotency-Key возвращает тот же заказ
        String orderKey = Fitclub.key();
        Response order = fc.api.post("/api/membership-orders", client.token(), Map.of("plan_id", planId), orderKey);
        assertThat(order.status()).as("%s", order).isEqualTo(201);
        assertThat(order.text("status")).isEqualTo("AWAITING_PAYMENT");
        Response orderReplay = fc.api.post("/api/membership-orders", client.token(), Map.of("plan_id", planId), orderKey);
        assertThat(orderReplay.id()).isEqualTo(order.id());

        // 3. Платёж и успешное тестовое завершение. Цена берётся из заказа, а не от клиента
        String paymentKey = Fitclub.key();
        Response payment = fc.api.post("/api/payments", client.token(),
                Map.of("membership_order_id", order.id()), paymentKey);
        assertThat(payment.status()).as("%s", payment).isEqualTo(201);
        assertThat(payment.body().path("amount_minor").asLong()).isEqualTo(20000);
        assertThat(payment.body().path("simulated").asBoolean()).isTrue();
        String simulateKey = Fitclub.key();
        Response simulated = fc.api.post("/api/payments/" + payment.id() + "/simulate", client.token(),
                Map.of("outcome", "SUCCESS"), simulateKey);
        assertThat(simulated.status()).as("%s", simulated).isEqualTo(200);
        assertThat(simulated.text("status")).isEqualTo("SUCCEEDED");

        // 4. Событие обработано: появился действующий абонемент (eventual consistency — ждём с ограничением)
        JsonNode membership = fc.awaitMembership(client, order.id());
        assertThat(membership.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(membership.path("visit_limit").asInt()).isEqualTo(2);
        assertThat(fc.api.get("/api/membership-orders/" + order.id(), client.token()).text("status")).isEqualTo("PAID");

        // 5. Запись: свободных мест и остаток посещений стало на одно меньше
        int availableBefore = fc.availability(sessionId).path("available").asInt();
        String bookingKey = Fitclub.key();
        Response booking = fc.book(client, sessionId, bookingKey);
        assertThat(booking.status()).as("%s", booking).isEqualTo(201);
        assertThat(booking.text("status")).isEqualTo("CONFIRMED");
        assertThat(fc.availability(sessionId).path("available").asInt()).isEqualTo(availableBefore - 1);
        assertThat(fc.usage(client).path("remaining").asInt()).isEqualTo(1);

        // 6. Подтверждение записи и активация абонемента приходят в Notification API
        await("уведомления о записи и абонементе").atMost(Fitclub.ASYNC).pollInterval(Duration.ofMillis(500))
                .until(() -> fc.hasNotification(client, "BOOKING_CONFIRMED", "E2E йога")
                        && fc.hasNotification(client, "MEMBERSHIP_ACTIVATED", ""));

        // 7. Повтор исходных запросов оплаты: тот же платёж и результат, второй активации нет
        Response paymentReplay = fc.api.post("/api/payments", client.token(),
                Map.of("membership_order_id", order.id()), paymentKey);
        assertThat(paymentReplay.id()).isEqualTo(payment.id());
        Response simulateReplay = fc.api.post("/api/payments/" + payment.id() + "/simulate", client.token(),
                Map.of("outcome", "SUCCESS"), simulateKey);
        assertThat(simulateReplay.status()).isEqualTo(200);
        assertThat(simulateReplay.text("completed_at")).isEqualTo(simulated.text("completed_at"));
        // Новый ключ с тем же исходом — идемпотентно по состоянию (200, без нового события);
        // противоположный исход для завершённого платежа — конфликт
        Response sameOutcome = fc.api.post("/api/payments/" + payment.id() + "/simulate", client.token(),
                Map.of("outcome", "SUCCESS"), Fitclub.key());
        assertThat(sameOutcome.status()).isEqualTo(200);
        assertThat(sameOutcome.text("completed_at")).isEqualTo(simulated.text("completed_at"));
        Response contrary = fc.api.post("/api/payments/" + payment.id() + "/simulate", client.token(),
                Map.of("outcome", "DECLINE"), Fitclub.key());
        assertThat(contrary.status()).isEqualTo(409);
        assertThat(contrary.code()).isEqualTo("PAYMENT_ALREADY_COMPLETED");
        Response memberships = fc.api.get("/api/memberships/me", client.token());
        assertThat(memberships.body().size()).as("один абонемент: %s", memberships).isEqualTo(1);
        assertThat(memberships.body().get(0).path("ends_at").asString())
                .as("срок не продлён повтором").isEqualTo(membership.path("ends_at").asString());
        assertThat(fc.countNotifications(client, "MEMBERSHIP_ACTIVATED")).isEqualTo(1);

        // 8. Отмена: посещение и место возвращаются один раз; повторная отмена ничего не меняет
        Response cancelled = fc.api.post("/api/bookings/" + booking.id() + "/cancel", client.token(), null);
        assertThat(cancelled.status()).as("%s", cancelled).isEqualTo(200);
        assertThat(cancelled.text("status")).isEqualTo("CANCELLED");
        Response cancelledAgain = fc.api.post("/api/bookings/" + booking.id() + "/cancel", client.token(), null);
        assertThat(cancelledAgain.status()).isEqualTo(200);
        assertThat(fc.usage(client).path("remaining").asInt()).isEqualTo(2);
        assertThat(fc.availability(sessionId).path("available").asInt()).isEqualTo(availableBefore);

        // Повтор исходного запроса записи возвращает сохранённый ответ, а не новую запись
        Response bookingReplay = fc.book(client, sessionId, bookingKey);
        assertThat(bookingReplay.id()).isEqualTo(booking.id());
        assertThat(fc.availability(sessionId).path("available").asInt()).isEqualTo(availableBefore);

        await("уведомление об отмене").atMost(Fitclub.ASYNC).pollInterval(Duration.ofMillis(500))
                .until(() -> fc.hasNotification(client, "BOOKING_CANCELLED", "E2E йога"));
    }
}
