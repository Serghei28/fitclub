package com.fitclub.schedule;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import com.fitclub.messaging.OutboxMessage;
import com.fitclub.messaging.OutboxRelay;
import com.fitclub.messaging.OutboxWriter;
import com.fitclub.testsupport.PostgresTestcontainers;
import com.fitclub.testsupport.RabbitTestcontainers;
import com.fitclub.testsupport.TestJwt;

/**
 * schedule-service целиком: настоящие PostgreSQL и RabbitMQ (топология из infrastructure),
 * auth-service заменён WireMock. Проверяется путь «HTTP → транзакция + outbox → RabbitMQ → очередь booking».
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestcontainers.class)
class ScheduleApiIT {

    static final String BOOKING_QUEUE = "booking-service.events";
    static final WireMockServer auth = new WireMockServer(wireMockConfig().dynamicPort());
    static final UUID TRAINER = UUID.randomUUID();
    static final UUID CLIENT_USER = UUID.randomUUID();
    static final String ADMIN_TOKEN = TestJwt.token(UUID.randomUUID(), "ADMIN");
    static final String CLIENT_TOKEN = TestJwt.token(CLIENT_USER, "CLIENT");
    static final String TRAINER_TOKEN = TestJwt.token(TRAINER, "CLIENT", "TRAINER");
    static final List<Map<String, Object>> received = new ArrayList<>();

    static {
        auth.start();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    OutboxWriter outboxWriter;

    @Autowired
    OutboxRelay outboxRelay;

    @Autowired
    TransactionTemplate transactionTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fitclub.security.jwt-public-key", TestJwt::publicKeyLocation);
        registry.add("fitclub.schedule.auth.base-url", auth::baseUrl);
        RabbitTestcontainers.register(registry);
    }

    @AfterAll
    static void stopAuth() {
        auth.stop();
    }

    @BeforeEach
    void stubAuth() {
        auth.resetAll();
        auth.stubFor(get(urlPathMatching("/internal/users/.*/trainer-status")).willReturn(aResponse().withStatus(404)));
        auth.stubFor(get(urlPathMatching("/internal/users/" + TRAINER + "/trainer-status")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"user_id\":\"" + TRAINER + "\",\"active\":true,\"trainer\":true,"
                        + "\"display_name\":\"Ольга Тренер\"}")));
    }

    ResultActions postJson(String path, String token, String json) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions getAs(String path, String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    String trainingType() throws Exception {
        return JsonPath.read(body(postJson("/api/training-types", ADMIN_TOKEN,
                "{\"name\":\"Йога " + UUID.randomUUID() + "\"}").andExpect(status().isCreated())), "$.id");
    }

    String sessionJson(String typeId, UUID trainer, Instant start, int capacity) {
        return """
                {"training_type_id":"%s","trainer_id":"%s","title":"Утренняя йога",
                 "starts_at":"%s","ends_at":"%s","capacity":%d}
                """.formatted(typeId, trainer, start, start.plus(Duration.ofHours(1)), capacity);
    }

    String createDraft(Instant start) throws Exception {
        return JsonPath.read(body(postJson("/api/sessions", ADMIN_TOKEN, sessionJson(trainingType(), TRAINER, start, 10))
                .andExpect(status().isCreated())), "$.id");
    }

    static Instant tomorrow() {
        return Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MINUTES);
    }

    /** Ждёт событие нужного типа для занятия в очереди booking-service (сообщения других тестов копятся в received). */
    Map<String, Object> awaitEvent(String eventType, String sessionId) {
        return await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).until(() -> {
            Message message;
            while ((message = rabbit.receive(BOOKING_QUEUE, 100)) != null) {
                Map<String, Object> envelope = JsonPath.read(new String(message.getBody(), StandardCharsets.UTF_8), "$");
                envelope.put("_amqp_message_id", message.getMessageProperties().getMessageId());
                envelope.put("_amqp_delivery_mode", String.valueOf(message.getMessageProperties().getReceivedDeliveryMode()));
                received.add(envelope);
            }
            return received.stream().filter(e -> eventType.equals(e.get("event_type"))
                    && sessionId.equals(e.get("aggregate_id"))).findFirst().orElse(null);
        }, e -> e != null);
    }

    @Nested
    class Authoring {

        @Test
        void adminCreatesDraftWithTrainerSnapshotFromAuth() throws Exception {
            postJson("/api/sessions", ADMIN_TOKEN, sessionJson(trainingType(), TRAINER, tomorrow(), 12))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.editable").value(true))
                    .andExpect(jsonPath("$.trainer_display_name").value("Ольга Тренер"))
                    .andExpect(jsonPath("$.training_type_name").exists());
        }

        @Test
        void nonTrainerCannotBeAssigned() throws Exception {
            postJson("/api/sessions", ADMIN_TOKEN, sessionJson(trainingType(), UUID.randomUUID(), tomorrow(), 12))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRAINER_NOT_ELIGIBLE"));
        }

        @Test
        void invalidPeriodAndCapacityAreRejected() throws Exception {
            String type = trainingType();
            Instant start = tomorrow();
            postJson("/api/sessions", ADMIN_TOKEN, """
                    {"training_type_id":"%s","trainer_id":"%s","title":"Йога","starts_at":"%s","ends_at":"%s","capacity":5}
                    """.formatted(type, TRAINER, start, start.minusSeconds(60)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SESSION_PERIOD"));
            postJson("/api/sessions", ADMIN_TOKEN, sessionJson(type, TRAINER, start, 0))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        void clientCannotCreateSessions() throws Exception {
            postJson("/api/sessions", CLIENT_TOKEN, sessionJson(UUID.randomUUID().toString(), TRAINER, tomorrow(), 5))
                    .andExpect(status().isForbidden());
        }

        @Test
        void authOutageGivesDependencyErrorNotFakeSuccess() throws Exception {
            auth.stubFor(get(urlPathMatching("/internal/users/.*/trainer-status")).willReturn(aResponse().withStatus(503)));
            postJson("/api/sessions", ADMIN_TOKEN, sessionJson(trainingType(), TRAINER, tomorrow(), 12))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_BAD_RESPONSE"));
        }
    }

    @Nested
    class Publication {

        @Test
        void publishWritesOutboxAndDeliversSessionPublishedToBookingQueue() throws Exception {
            String id = createDraft(tomorrow());

            postJson("/api/sessions/" + id + "/publish", ADMIN_TOKEN, "")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PUBLISHED"))
                    .andExpect(jsonPath("$.version").value(1));

            Map<String, Object> event = awaitEvent("SessionPublished", id);
            assertThat(event).containsEntry("schema_version", 1).containsEntry("producer", "schedule-service")
                    .containsEntry("aggregate_version", 1).containsKeys("event_id", "occurred_at", "correlation_id",
                            "causation_id");
            assertThat(event.get("_amqp_message_id")).isEqualTo(event.get("event_id"));
            assertThat(event.get("_amqp_delivery_mode")).isEqualTo("PERSISTENT");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) event.get("payload");
            assertThat(payload).containsEntry("session_id", id).containsEntry("capacity", 10)
                    .containsEntry("trainer_display_name", "Ольга Тренер");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                    "select count(*) from outbox_events where aggregate_id = ?::uuid and published_at is not null",
                    Integer.class, id)).isEqualTo(1));
        }

        @Test
        void publishedSessionIsFrozenForEditing() throws Exception {
            String id = createDraft(tomorrow());
            postJson("/api/sessions/" + id + "/publish", ADMIN_TOKEN, "").andExpect(status().isOk());
            mvc.perform(patch("/api/sessions/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":50}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SESSION_NOT_EDITABLE"));
        }

        @Test
        void pastSessionCannotBePublished() throws Exception {
            String id = createDraft(Instant.now().minus(Duration.ofHours(2)));
            postJson("/api/sessions/" + id + "/publish", ADMIN_TOKEN, "")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SESSION_IN_PAST"));
            assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?::uuid",
                    Integer.class, id)).isZero();
        }

        @Test
        void cancellingPublishedSessionEmitsSessionCancelledWithNextVersion() throws Exception {
            String id = createDraft(tomorrow());
            postJson("/api/sessions/" + id + "/publish", ADMIN_TOKEN, "").andExpect(status().isOk());
            postJson("/api/sessions/" + id + "/cancel", ADMIN_TOKEN, "{\"reason\":\"Тренер заболел\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
            postJson("/api/sessions/" + id + "/cancel", ADMIN_TOKEN, "{}").andExpect(status().isOk());

            Map<String, Object> event = awaitEvent("SessionCancelled", id);
            assertThat(event).containsEntry("aggregate_version", 2);
            assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?::uuid "
                    + "and event_type = 'SessionCancelled'", Integer.class, id)).isEqualTo(1);
        }
    }

    @Nested
    class Visibility {

        @Test
        void draftsAreVisibleOnlyToAdmin() throws Exception {
            String id = createDraft(tomorrow());
            getAs("/api/sessions/" + id, CLIENT_TOKEN).andExpect(status().isNotFound());
            getAs("/api/sessions/" + id, ADMIN_TOKEN).andExpect(status().isOk());
            String list = body(getAs("/api/sessions?size=100", CLIENT_TOKEN).andExpect(status().isOk()));
            List<String> statuses = JsonPath.read(list, "$.items[*].status");
            assertThat(statuses).doesNotContain("DRAFT");
        }

        @Test
        void trainerSeesOwnPublishedSessions() throws Exception {
            String id = createDraft(tomorrow());
            postJson("/api/sessions/" + id + "/publish", ADMIN_TOKEN, "").andExpect(status().isOk());
            List<String> ids = JsonPath.read(body(getAs("/api/sessions/mine?size=100", TRAINER_TOKEN)
                    .andExpect(status().isOk())), "$.items[*].id");
            assertThat(ids).contains(id);
            getAs("/api/sessions/mine", CLIENT_TOKEN).andExpect(status().isForbidden());
        }
    }

    @Nested
    class OutboxReliability {

        @Test
        void operationSucceedsWhileBrokerIsDownAndEventIsDeliveredAfterRecovery() throws Exception {
            String id = createDraft(tomorrow());
            RabbitTestcontainers.stopBroker();
            try {
                // Операция не зависит от брокера: изменение и событие сохраняются в своей БД
                postJson("/api/sessions/" + id + "/publish", ADMIN_TOKEN, "").andExpect(status().isOk());
                mvc.perform(MockMvcRequestBuilders.get("/ready")).andExpect(status().isOk());
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    mvc.perform(MockMvcRequestBuilders.get("/actuator/health/messaging"))
                            .andExpect(status().isServiceUnavailable());
                    assertThat(jdbc.queryForObject("select attempts from outbox_events where aggregate_id = ?::uuid",
                            Integer.class, id)).isGreaterThanOrEqualTo(1);
                });
                assertThat(jdbc.queryForObject("select published_at is null from outbox_events "
                        + "where aggregate_id = ?::uuid", Boolean.class, id)).isTrue();
            } finally {
                RabbitTestcontainers.startBroker();
            }
            // Отложенный повтор: сбрасываем паузу, чтобы не ждать экспоненциальный backoff
            jdbc.update("update outbox_events set next_attempt_at = now() where aggregate_id = ?::uuid", id);
            Map<String, Object> event = awaitEvent("SessionPublished", id);
            assertThat(event).containsEntry("aggregate_id", id);
        }

        @Test
        void unroutableEventStaysPendingInsteadOfBeingLost() {
            UUID aggregate = UUID.randomUUID();
            transactionTemplate.executeWithoutResult(status -> outboxWriter.append(new OutboxMessage("TestEvent", 1,
                    "schedule.no-such-binding", "Test", aggregate, 1, Map.of("x", 1))));

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                outboxRelay.publishBatch();
                Map<String, Object> row = jdbc.queryForMap("select published_at, attempts, last_error "
                        + "from outbox_events where aggregate_id = ?", aggregate);
                assertThat(row.get("published_at")).isNull();
                assertThat((Integer) row.get("attempts")).isGreaterThanOrEqualTo(1);
                assertThat((String) row.get("last_error")).startsWith("unroutable");
            });
        }
    }
}
