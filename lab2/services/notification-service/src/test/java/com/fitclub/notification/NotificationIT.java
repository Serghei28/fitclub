package com.fitclub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fitclub.notification.application.ReminderProcessor;
import com.fitclub.testsupport.PostgresTestcontainers;
import com.fitclub.testsupport.RabbitTestcontainers;
import com.fitclub.testsupport.TestJwt;

/** notification-service на настоящих RabbitMQ и PostgreSQL; время напоминаний управляется через Clock. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestcontainers.class, NotificationIT.ClockConfig.class})
class NotificationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    MutableClock clock;

    @Autowired
    ReminderProcessor reminders;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fitclub.security.jwt-public-key", TestJwt::publicKeyLocation);
        RabbitTestcontainers.register(registry);
    }

    static final class MutableClock extends Clock {
        volatile Instant now = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @AfterEach
    void resetClock() {
        clock.now = Instant.now();
    }

    UUID publish(String routingKey, String type, UUID aggregate, long version, String payload) {
        return publish(routingKey, type, aggregate, version, payload, UUID.randomUUID());
    }

    UUID publish(String routingKey, String type, UUID aggregate, long version, String payload, UUID eventId) {
        String envelope = """
                {"event_id":"%s","event_type":"%s","schema_version":1,"occurred_at":"%s","producer":"test",
                 "aggregate_id":"%s","aggregate_version":%d,"correlation_id":"c","causation_id":"c","payload":%s}
                """.formatted(eventId, type, Instant.now(), aggregate, version, payload);
        rabbit.send("fitclub.events", routingKey, MessageBuilder.withBody(envelope.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(eventId.toString()).build());
        return eventId;
    }

    String bookingPayload(UUID booking, UUID user, Instant startsAt, String reason) {
        return """
                {"booking_id":"%s","session_id":"%s","user_id":"%s","membership_id":"%s","session_title":"Пилатес",
                 "starts_at":"%s","reason":%s}""".formatted(booking, UUID.randomUUID(), user, UUID.randomUUID(),
                startsAt, reason == null ? "null" : "\"" + reason + "\"");
    }

    int notifications(UUID user) {
        return jdbc.queryForObject("select count(*) from notifications where user_id = ?", Integer.class, user);
    }

    void awaitNotifications(UUID user, int count) {
        await().atMost(Duration.ofSeconds(15)).until(() -> notifications(user) == count);
    }

    ResultActions getAs(UUID user, String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.token(user, "CLIENT")));
    }

    @Test
    void membershipActivationNotificationIsAvailableThroughApiAndDeduplicated() throws Exception {
        UUID user = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        String payload = """
                {"membership_id":"%s","order_id":"%s","user_id":"%s","plan_name":"8 посещений",
                 "starts_at":"2026-11-01T10:00:00Z","ends_at":"2026-12-01T10:00:00Z","visit_limit":8}
                """.formatted(membership, UUID.randomUUID(), user);
        UUID eventId = publish("membership.membership.activated", "MembershipActivated", membership, 1, payload);
        awaitNotifications(user, 1);
        // Повтор с тем же event_id и новое событие о том же факте не создают дубль
        publish("membership.membership.activated", "MembershipActivated", membership, 1, payload, eventId);
        UUID second = publish("membership.membership.activated", "MembershipActivated", membership, 1, payload);
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "select count(*) from processed_events where event_id = ?", Integer.class, second) == 1);
        assertThat(notifications(user)).isEqualTo(1);

        getAs(user, "/api/notifications")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("MEMBERSHIP_ACTIVATED"))
                .andExpect(jsonPath("$.items[0].read").value(false))
                .andExpect(jsonPath("$.items[0].body").value(org.hamcrest.Matchers.containsString("01.11.2026 12:00")));
        getAs(user, "/api/notifications/unread-count").andExpect(jsonPath("$.unread").value(1));
    }

    @Test
    void onlyRecipientCanMarkAsRead() throws Exception {
        UUID user = UUID.randomUUID();
        UUID booking = UUID.randomUUID();
        publish("booking.booking.confirmed", "BookingConfirmed", booking, 1,
                bookingPayload(booking, user, Instant.now().plus(Duration.ofDays(2)), null));
        awaitNotifications(user, 1);
        String id = JsonPath.read(getAs(user, "/api/notifications").andReturn().getResponse().getContentAsString(),
                "$.items[0].id");

        mvc.perform(patch("/api/notifications/" + id + "/read").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + TestJwt.token(UUID.randomUUID(), "CLIENT"))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/notifications/" + id + "/read").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + TestJwt.token(user, "CLIENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.read").value(true));
        getAs(user, "/api/notifications/unread-count").andExpect(jsonPath("$.unread").value(0));
        getAs(user, "/api/notifications?unread=true").andExpect(jsonPath("$.total_elements").value(0));
    }

    @Test
    void reminderIsSentOnceAtLeadTimeAndCancellationSuppressesAnother() throws Exception {
        UUID user = UUID.randomUUID();
        Instant start = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
        UUID kept = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        publish("booking.booking.confirmed", "BookingConfirmed", kept, 1, bookingPayload(kept, user, start, null));
        publish("booking.booking.confirmed", "BookingConfirmed", cancelled, 1,
                bookingPayload(cancelled, user, start, null));
        awaitNotifications(user, 2);
        publish("booking.booking.cancelled", "BookingCancelled", cancelled, 2,
                bookingPayload(cancelled, user, start, "USER"));
        awaitNotifications(user, 3);

        clock.now = start.minus(Duration.ofMinutes(61));
        reminders.processDue(50);
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = "
                + "'SESSION_REMINDER'", Integer.class, user)).isZero();

        clock.now = start.minus(Duration.ofMinutes(60));
        reminders.processDue(50);
        reminders.processDue(50);
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = "
                + "'SESSION_REMINDER'", Integer.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from reminder_jobs where booking_id = ?", String.class, kept))
                .isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select status from reminder_jobs where booking_id = ?", String.class,
                cancelled)).isEqualTo("CANCELLED");
    }

    @Test
    void reminderStoredBeforeRestartIsProcessedFromDatabase() {
        // Задача, созданная до перезапуска сервиса: в памяти о ней ничего нет, она есть только в БД
        UUID user = UUID.randomUUID();
        UUID booking = UUID.randomUUID();
        Instant start = Instant.now().plus(Duration.ofHours(2));
        jdbc.update("""
                insert into reminder_jobs (id, booking_id, user_id, session_title, starts_at, remind_at, status,
                    attempts, created_at) values (?, ?, ?, 'Йога', ?, ?, 'PENDING', 0, now() - interval '1 day')
                """, UUID.randomUUID(), booking, user, Timestamp.from(start), Timestamp.from(start.minusSeconds(3600)));

        clock.now = start.minusSeconds(3600);
        reminders.processDue(50);

        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = "
                + "'SESSION_REMINDER'", Integer.class, user)).isEqualTo(1);
    }

    @Test
    void lateConfirmationAfterCancellationCreatesNothing() throws Exception {
        UUID user = UUID.randomUUID();
        UUID booking = UUID.randomUUID();
        Instant start = Instant.now().plus(Duration.ofDays(1));
        publish("booking.booking.cancelled", "BookingCancelled", booking, 2,
                bookingPayload(booking, user, start, "SESSION_CANCELLED"));
        awaitNotifications(user, 1);
        UUID late = publish("booking.booking.confirmed", "BookingConfirmed", booking, 1,
                bookingPayload(booking, user, start, null));
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "select count(*) from processed_events where event_id = ?", Integer.class, late) == 1);

        assertThat(notifications(user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from reminder_jobs where booking_id = ?", Integer.class,
                booking)).isZero();
        getAs(user, "/api/notifications").andExpect(jsonPath("$.items[0].type").value("SESSION_CANCELLED"));
    }
}
