package com.fitclub.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fitclub.membership.application.PlaceOrderUseCase;
import com.fitclub.membership.application.PlanAdminService;
import com.fitclub.testsupport.PostgresTestcontainers;
import com.fitclub.testsupport.RabbitTestcontainers;
import com.fitclub.testsupport.TestJwt;

/**
 * Потребитель событий оплаты на настоящих RabbitMQ и PostgreSQL: события публикуются в fitclub.events
 * так же, как это делает payment-service, и приходят в очередь membership-service.events.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestcontainers.class)
class PaymentEventsIT {

    static final List<Map<String, Object>> notificationQueue = new ArrayList<>();
    static final List<Map<String, Object>> deadLetters = new ArrayList<>();

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlanAdminService plans;

    @Autowired
    PlaceOrderUseCase placeOrder;

    UUID user;
    UUID orderId;
    UUID planId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fitclub.security.jwt-public-key", TestJwt::publicKeyLocation);
        RabbitTestcontainers.register(registry);
    }

    @BeforeEach
    void order() {
        user = UUID.randomUUID();
        planId = plans.create(new PlanAdminService.CreatePlan("Тариф " + UUID.randomUUID(), 30, 8, 45000, "MDL")).id();
        orderId = placeOrder.place(user, UUID.randomUUID(), new PlaceOrderUseCase.Request(planId)).body().id();
    }

    String paymentEvent(UUID eventId, String type, UUID paymentId, long amount, Instant completedAt) {
        return """
                {"event_id":"%s","event_type":"%s","schema_version":1,"occurred_at":"%s","producer":"payment-service",
                 "aggregate_id":"%s","aggregate_version":1,"correlation_id":"test-corr-%s","causation_id":"test-corr",
                 "payload":{"payment_id":"%s","order_id":"%s","user_id":"%s","amount_minor":%d,"currency":"MDL",
                            "completed_at":"%s"}}
                """.formatted(eventId, type, Instant.now(), paymentId, eventId, paymentId, orderId, user, amount,
                completedAt);
    }

    void publish(String routingKey, String body, UUID messageId) {
        Message message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(messageId.toString()).build();
        rabbit.send("fitclub.events", routingKey, message);
    }

    String orderStatus() {
        return jdbc.queryForObject("select status from membership_orders where id = ?", String.class, orderId);
    }

    int membershipsOfOrder() {
        return jdbc.queryForObject("select count(*) from memberships where order_id = ?", Integer.class, orderId);
    }

    static Map<String, Object> drainUntil(String queue, List<Map<String, Object>> sink, String key, String value) {
        return await().atMost(Duration.ofSeconds(20)).until(() -> {
            Message m;
            while ((m = RabbitHolder.template.receive(queue, 100)) != null) {
                String body = new String(m.getBody(), StandardCharsets.UTF_8);
                try {
                    sink.add(JsonPath.read(body, "$"));
                } catch (RuntimeException notJson) {
                    sink.add(Map.of("raw", body));
                }
            }
            return sink.stream().filter(e -> String.valueOf(e).contains(key) && String.valueOf(e).contains(value))
                    .findFirst().orElse(null);
        }, e -> e != null);
    }

    /** Доступ к RabbitTemplate из статического помощника. */
    static final class RabbitHolder {
        static RabbitTemplate template;
    }

    @BeforeEach
    void holder() {
        RabbitHolder.template = rabbit;
    }

    @Test
    void successActivatesExactlyOneMembershipStartingAtPaymentTime() {
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant paidAt = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.MILLIS);
        String event = paymentEvent(eventId, "PaymentSucceeded", paymentId, 45000, paidAt);

        publish("payment.payment.succeeded", event, eventId);
        await().atMost(Duration.ofSeconds(15)).until(() -> "PAID".equals(orderStatus()));

        Map<String, Object> m = jdbc.queryForMap("select starts_at, ends_at, payment_id from memberships where order_id = ?",
                orderId);
        // Начало — момент оплаты из события, а не время обработки
        assertThat(((Timestamp) m.get("starts_at")).toInstant()).isEqualTo(paidAt);
        assertThat(((Timestamp) m.get("ends_at")).toInstant()).isEqualTo(paidAt.plus(Duration.ofDays(30)));

        // Повторная доставка того же события и новое событие о том же платеже не создают второй абонемент
        publish("payment.payment.succeeded", event, eventId);
        UUID newEventId = UUID.randomUUID();
        publish("payment.payment.succeeded", paymentEvent(newEventId, "PaymentSucceeded", paymentId, 45000,
                Instant.now()), newEventId);
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "select count(*) from processed_events where event_id = ?", Integer.class, newEventId) == 1);
        assertThat(membershipsOfOrder()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class,
                eventId)).isEqualTo(1);
        assertThat(((Timestamp) jdbc.queryForObject("select ends_at from memberships where order_id = ?",
                Timestamp.class, orderId)).toInstant()).isEqualTo(paidAt.plus(Duration.ofDays(30)));

        // MembershipActivated опубликован через outbox и пришёл в очередь notification-service
        Map<String, Object> activated = drainUntil("notification-service.events", notificationQueue,
                "MembershipActivated", orderId.toString());
        assertThat(String.valueOf(activated.get("causation_id"))).isEqualTo(eventId.toString());
        assertThat(String.valueOf(activated.get("correlation_id"))).isEqualTo("test-corr-" + eventId);
    }

    @Test
    void amountMismatchIsNotAppliedAndGoesToDlq() {
        UUID eventId = UUID.randomUUID();
        publish("payment.payment.succeeded", paymentEvent(eventId, "PaymentSucceeded", UUID.randomUUID(), 1, Instant.now()),
                eventId);

        drainUntil("membership-service.events.dlq", deadLetters, "event_id", eventId.toString());
        assertThat(orderStatus()).isEqualTo("AWAITING_PAYMENT");
        assertThat(membershipsOfOrder()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class,
                eventId)).isZero();
    }

    @Test
    void malformedMessageGoesToDlqWithoutBlockingQueue() {
        UUID messageId = UUID.randomUUID();
        publish("payment.payment.succeeded", "{not-json " + messageId, messageId);
        drainUntil("membership-service.events.dlq", deadLetters, "raw", messageId.toString());

        // Очередь не заблокирована испорченным сообщением: следующее событие обрабатывается
        UUID eventId = UUID.randomUUID();
        publish("payment.payment.succeeded", paymentEvent(eventId, "PaymentSucceeded", UUID.randomUUID(), 45000,
                Instant.now()), eventId);
        await().atMost(Duration.ofSeconds(15)).until(() -> "PAID".equals(orderStatus()));
    }

    @Test
    void declineMarksOrderFailedAllowsNewOrderAndLateDeclineIsIgnored() {
        UUID eventId = UUID.randomUUID();
        publish("payment.payment.declined", paymentEvent(eventId, "PaymentDeclined", UUID.randomUUID(), 45000,
                Instant.now()), eventId);
        await().atMost(Duration.ofSeconds(15)).until(() -> "PAYMENT_FAILED".equals(orderStatus()));
        assertThat(membershipsOfOrder()).isZero();

        // После отказа можно оформить новый заказ
        UUID newOrder = placeOrder.place(user, UUID.randomUUID(), new PlaceOrderUseCase.Request(planId)).body().id();
        assertThat(newOrder).isNotEqualTo(orderId);
    }

    @Test
    void declineAfterSuccessDoesNotRevokeMembership() {
        UUID paymentId = UUID.randomUUID();
        UUID success = UUID.randomUUID();
        publish("payment.payment.succeeded", paymentEvent(success, "PaymentSucceeded", paymentId, 45000, Instant.now()),
                success);
        await().atMost(Duration.ofSeconds(15)).until(() -> "PAID".equals(orderStatus()));

        UUID decline = UUID.randomUUID();
        publish("payment.payment.declined", paymentEvent(decline, "PaymentDeclined", paymentId, 45000, Instant.now()),
                decline);
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "select count(*) from processed_events where event_id = ?", Integer.class, decline) == 1);
        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(membershipsOfOrder()).isEqualTo(1);
    }
}
