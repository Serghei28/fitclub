package com.fitclub.booking;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;

import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Общая обвязка интеграционных тестов booking-service: управляемые часы, WireMock-Membership, события расписания. */
final class BookingTestSupport {

    static final WireMockServer membership = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        membership.start();
    }

    private BookingTestSupport() {
    }

    /** Часы, которые тест может переставить: граница окна отмены проверяется без ожидания реального времени. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        void set(Instant instant) {
            now = instant;
        }

        void reset() {
            now = Instant.now();
        }

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

    static void stubNoMembershipByDefault() {
        membership.resetAll();
        membership.stubFor(get(urlPathEqualTo("/internal/memberships/eligibility")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"eligible\":false,\"reason\":\"NO_VALID_MEMBERSHIP\"}")));
    }

    /** У пользователя действующий абонемент с лимитом (null — безлимит). */
    static UUID stubMembership(UUID user, Integer visitLimit) {
        UUID membershipId = UUID.randomUUID();
        String limit = visitLimit == null ? "null" : visitLimit.toString();
        membership.stubFor(get(urlPathEqualTo("/internal/memberships/eligibility"))
                .withQueryParam("user_id", equalTo(user.toString()))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"eligible":true,"membership_id":"%s","starts_at":"2026-01-01T00:00:00Z",
                         "ends_at":"2030-01-01T00:00:00Z","visit_limit":%s}""".formatted(membershipId, limit))));
        return membershipId;
    }

    static void publishSession(RabbitTemplate rabbit, UUID sessionId, UUID trainerId, int capacity, Instant startsAt,
            long version) {
        send(rabbit, "schedule.session.published", "SessionPublished", sessionId, version, """
                {"session_id":"%s","training_type_id":"%s","title":"Йога","trainer_id":"%s",
                 "trainer_display_name":"Ольга Тренер","starts_at":"%s","ends_at":"%s","capacity":%d}
                """.formatted(sessionId, UUID.randomUUID(), trainerId, startsAt, startsAt.plusSeconds(3600), capacity));
    }

    static void cancelSession(RabbitTemplate rabbit, UUID sessionId, long version) {
        send(rabbit, "schedule.session.cancelled", "SessionCancelled", sessionId, version, """
                {"session_id":"%s","cancelled_at":"%s","reason":"Тренер заболел"}""".formatted(sessionId, Instant.now()));
    }

    private static void send(RabbitTemplate rabbit, String routingKey, String type, UUID aggregate, long version,
            String payload) {
        UUID eventId = UUID.randomUUID();
        String envelope = """
                {"event_id":"%s","event_type":"%s","schema_version":1,"occurred_at":"%s","producer":"schedule-service",
                 "aggregate_id":"%s","aggregate_version":%d,"correlation_id":"test","causation_id":"test","payload":%s}
                """.formatted(eventId, type, Instant.now(), aggregate, version, payload);
        rabbit.send("fitclub.events", routingKey, MessageBuilder.withBody(envelope.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(eventId.toString()).build());
    }

    static void awaitInventory(JdbcTemplate jdbc, UUID sessionId, String status, long version) {
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "select count(*) from session_inventory where session_id = ? and status = ? and aggregate_version = ?",
                Integer.class, sessionId, status, version) == 1);
    }
}
