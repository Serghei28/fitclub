package com.fitclub.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import com.fitclub.e2e.support.Api.Response;
import com.fitclub.e2e.support.Fitclub;
import com.fitclub.e2e.support.Fitclub.User;

/**
 * Сценарии сбоев на изолированном стенде: контейнер останавливается командой docker compose stop,
 * после проверки запускается снова (в finally — даже если проверка упала).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FailureScenariosIT {

    private static final Duration RECOVERY = Duration.ofMinutes(3);

    private final Fitclub fc = new Fitclub();
    private User trainer;
    private UUID planId;

    @BeforeAll
    void catalog() {
        trainer = fc.trainer("failure-trainer");
        planId = fc.plan("E2E сбои", 10, 15000);
    }

    @Test
    @Order(1)
    @DisplayName("Notification остановлен: запись проходит, уведомление сохраняется в очереди и появляется после запуска")
    void notificationDown() {
        UUID sessionId = fc.publishedSession(trainer, "E2E без уведомлений", 80, 5);
        User client = fc.clientWithMembership("notif", planId);

        fc.stack.stop("notification-service");
        try {
            Response booking = fc.book(client, sessionId, Fitclub.key());
            assertThat(booking.status()).as("запись не зависит от Notification: %s", booking).isEqualTo(201);
            Response notifications = fc.api.get("/api/notifications", client.token());
            assertThat(notifications.status()).isEqualTo(503);
            assertThat(notifications.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        } finally {
            fc.stack.start("notification-service");
        }

        await("Notification обработал сохранённое в очереди событие").atMost(RECOVERY).pollInterval(Duration.ofSeconds(1))
                .until(() -> fc.hasNotification(client, "BOOKING_CONFIRMED", "E2E без уведомлений"));
        assertThat(fc.countNotifications(client, "BOOKING_CONFIRMED")).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("Membership остановлен: запись и заказ отклоняются управляемым 503 без частичных изменений")
    void membershipDown() {
        UUID sessionId = fc.publishedSession(trainer, "E2E без абонементов", 82, 5);
        User client = fc.clientWithMembership("member", planId);
        int availableBefore = fc.availability(sessionId).path("available").asInt();

        fc.stack.stop("membership-service");
        try {
            Response booking = fc.book(client, sessionId, Fitclub.key());
            assertThat(booking.status()).as("eligibility недоступна: %s", booking).isIn(503, 504);
            assertThat(booking.code()).startsWith("DEPENDENCY_");
            assertThat(booking.body().toString()).doesNotContain("Exception");
            Response order = fc.api.post("/api/membership-orders", fc.client("late").token(),
                    Map.of("plan_id", planId), Fitclub.key());
            assertThat(order.status()).isEqualTo(503);
            // Место не занято и запись не создана: решение принимается до транзакции Booking
            assertThat(fc.availability(sessionId).path("available").asInt()).isEqualTo(availableBefore);
            assertThat(fc.api.get("/api/bookings/me", client.token()).body().path("total_elements").asInt()).isZero();
        } finally {
            fc.stack.start("membership-service");
        }

        await("Membership снова отвечает, запись проходит").atMost(RECOVERY).pollInterval(Duration.ofSeconds(2))
                .until(() -> fc.book(client, sessionId, Fitclub.key()).status() == 201);
        assertThat(fc.availability(sessionId).path("available").asInt()).isEqualTo(availableBefore - 1);
    }

    @Test
    @Order(3)
    @DisplayName("RabbitMQ остановлен: оплата фиксируется в outbox, после восстановления брокера — одна активация")
    void brokerDown() {
        User client = fc.client("broker");
        Response order = fc.api.post("/api/membership-orders", client.token(), Map.of("plan_id", planId), Fitclub.key());
        assertThat(order.status()).isEqualTo(201);
        Response payment = fc.api.post("/api/payments", client.token(),
                Map.of("membership_order_id", order.id()), Fitclub.key());
        assertThat(payment.status()).isEqualTo(201);
        UUID paymentId = payment.id();
        String simulateKey = Fitclub.key();
        String pendingOutbox = "select count(*) from outbox_events where published_at is null and envelope::text like '%"
                + paymentId + "%'";
        String publishedOutbox = pendingOutbox.replace("is null", "is not null");

        fc.stack.stop("rabbitmq");
        try {
            Response simulated = fc.api.post("/api/payments/" + paymentId + "/simulate", client.token(),
                    Map.of("outcome", "SUCCESS"), simulateKey);
            assertThat(simulated.status()).as("локальная транзакция Payment без брокера: %s", simulated).isEqualTo(200);
            assertThat(simulated.text("status")).isEqualTo("SUCCEEDED");
            // Событие сохранено в той же транзакции и ждёт брокера; абонемента пока нет
            assertThat(fc.stack.sql("payment_db", pendingOutbox)).isEqualTo("1");
            assertThat(fc.membershipFor(client, order.id()).isMissingNode()).isTrue();
            // Повтор запроса клиентом во время сбоя — тот же результат, а не второй платёж
            Response replay = fc.api.post("/api/payments/" + paymentId + "/simulate", client.token(),
                    Map.of("outcome", "SUCCESS"), simulateKey);
            assertThat(replay.text("completed_at")).isEqualTo(simulated.text("completed_at"));
        } finally {
            fc.stack.start("rabbitmq");
        }

        await("relay отправил событие после восстановления брокера").atMost(RECOVERY).pollInterval(Duration.ofSeconds(2))
                .until(() -> fc.stack.sql("payment_db", publishedOutbox).equals("1"));
        assertThat(fc.awaitMembershipLong(client, order.id()).path("status").asString()).isEqualTo("ACTIVE");
        assertThat(fc.api.get("/api/memberships/me", client.token()).body().size()).isEqualTo(1);
        assertThat(fc.stack.sql("payment_db", "select count(*) from payments where membership_order_id = '"
                + order.id() + "'")).isEqualTo("1");
        assertThat(fc.stack.sql("membership_db", "select count(*) from memberships where order_id = '"
                + order.id() + "'")).isEqualTo("1");
    }
}
