package com.fitclub.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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

import com.fitclub.common.idempotency.IdempotencyStore.Outcome;
import com.fitclub.payment.application.CreatePaymentUseCase;
import com.fitclub.payment.application.PaymentView;
import com.fitclub.testsupport.PostgresTestcontainers;
import com.fitclub.testsupport.RabbitTestcontainers;
import com.fitclub.testsupport.TestJwt;

/** payment-service: PostgreSQL + RabbitMQ, membership-service заменён WireMock. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestcontainers.class)
class PaymentApiIT {

    static final String PAYMENT_TOKEN = "test-service-token-payment-to-membership-0123456789abcdef";
    static final WireMockServer membership = new WireMockServer(wireMockConfig().dynamicPort());
    static final List<Map<String, Object>> received = new ArrayList<>();

    static {
        membership.start();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    CreatePaymentUseCase createPayment;

    UUID user;
    UUID orderId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fitclub.security.jwt-public-key", TestJwt::publicKeyLocation);
        registry.add("fitclub.payment.membership.base-url", membership::baseUrl);
        RabbitTestcontainers.register(registry);
    }

    @AfterAll
    static void stop() {
        membership.stop();
    }

    @BeforeEach
    void order() {
        user = UUID.randomUUID();
        orderId = UUID.randomUUID();
        stubOrder(orderId, user, "AWAITING_PAYMENT");
    }

    void stubOrder(UUID order, UUID owner, String status) {
        membership.stubFor(get(urlEqualTo("/internal/membership-orders/" + order + "/payment-details"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"order_id":"%s","user_id":"%s","status":"%s","amount_minor":45000,"currency":"MDL",
                         "plan_name":"8 посещений"}""".formatted(order, owner, status))));
    }

    ResultActions createAs(UUID who, UUID key, String body) throws Exception {
        return mvc.perform(post("/api/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.token(who, "CLIENT"))
                .header("Idempotency-Key", key.toString()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions create(UUID key) throws Exception {
        return createAs(user, key, "{\"membership_order_id\":\"" + orderId + "\"}");
    }

    ResultActions simulate(UUID who, String paymentId, String outcome, UUID key) throws Exception {
        return mvc.perform(post("/api/payments/" + paymentId + "/simulate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.token(who, "CLIENT"))
                .header("Idempotency-Key", key.toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"" + outcome + "\"}"));
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    Map<String, Object> awaitPaymentEvent(String type, String paymentId) {
        return await().atMost(Duration.ofSeconds(15)).until(() -> {
            Message m;
            while ((m = rabbit.receive("membership-service.events", 100)) != null) {
                received.add(JsonPath.read(new String(m.getBody(), StandardCharsets.UTF_8), "$"));
            }
            return received.stream().filter(e -> type.equals(e.get("event_type"))
                    && paymentId.equals(e.get("aggregate_id"))).findFirst().orElse(null);
        }, e -> e != null);
    }

    @Test
    void amountComesFromMembershipAndServiceTokenIsSent() throws Exception {
        create(UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount_minor").value(45000))
                .andExpect(jsonPath("$.currency").value("MDL"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.simulated").value(true));
        membership.verify(getRequestedFor(urlEqualTo("/internal/membership-orders/" + orderId + "/payment-details"))
                .withHeader("X-Service-Token", equalTo(PAYMENT_TOKEN)));
    }

    @Test
    void idempotencyReplayAndConflict() throws Exception {
        UUID key = UUID.randomUUID();
        String id = JsonPath.read(body(create(key).andExpect(status().isCreated())), "$.id");
        create(key).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));
        UUID otherOrder = UUID.randomUUID();
        stubOrder(otherOrder, user, "AWAITING_PAYMENT");
        createAs(user, key, "{\"membership_order_id\":\"" + otherOrder + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void oneOrderHasAtMostOnePaymentEvenWithDifferentKeysInParallel() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome<PaymentView>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return createPayment.create(user, UUID.randomUUID(), new CreatePaymentUseCase.Request(orderId));
            }));
        }
        start.countDown();
        List<UUID> ids = new ArrayList<>();
        for (Future<Outcome<PaymentView>> f : futures) {
            ids.add(f.get().body().id());
        }
        pool.shutdown();
        assertThat(ids.stream().distinct().count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payments where membership_order_id = ?", Integer.class,
                orderId)).isEqualTo(1);
    }

    @Test
    void foreignOrUnknownOrderIsNotFound() throws Exception {
        createAs(UUID.randomUUID(), UUID.randomUUID(), "{\"membership_order_id\":\"" + orderId + "\"}")
                .andExpect(status().isNotFound());
        UUID unknown = UUID.randomUUID();
        membership.stubFor(get(urlEqualTo("/internal/membership-orders/" + unknown + "/payment-details"))
                .willReturn(aResponse().withStatus(404)));
        createAs(user, UUID.randomUUID(), "{\"membership_order_id\":\"" + unknown + "\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void paidOrderIsNotPayable() throws Exception {
        stubOrder(orderId, user, "PAID");
        create(UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));
    }

    @Test
    void slowMembershipGivesTimeoutAndNoPayment() throws Exception {
        membership.stubFor(get(urlEqualTo("/internal/membership-orders/" + orderId + "/payment-details"))
                .willReturn(aResponse().withFixedDelay(2000).withBody("{}")));
        create(UUID.randomUUID()).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_TIMEOUT"));
        assertThat(jdbc.queryForObject("select count(*) from payments where membership_order_id = ?", Integer.class,
                orderId)).isZero();
    }

    @Test
    void successfulSimulationPublishesPaymentSucceededOnce() throws Exception {
        String id = JsonPath.read(body(create(UUID.randomUUID())), "$.id");
        UUID key = UUID.randomUUID();

        simulate(user, id, "SUCCESS", key).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        simulate(user, id, "SUCCESS", key).andExpect(header().string("Idempotent-Replayed", "true"));
        simulate(user, id, "SUCCESS", UUID.randomUUID()).andExpect(status().isOk());
        simulate(user, id, "DECLINE", UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_COMPLETED"));

        Map<String, Object> event = awaitPaymentEvent("PaymentSucceeded", id);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertThat(payload).containsEntry("order_id", orderId.toString()).containsEntry("user_id", user.toString())
                .containsEntry("amount_minor", 45000).containsEntry("currency", "MDL").containsKey("completed_at");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?::uuid",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void declineAndForeignSimulation() throws Exception {
        String id = JsonPath.read(body(create(UUID.randomUUID())), "$.id");
        simulate(UUID.randomUUID(), id, "SUCCESS", UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get("/api/payments/" + id).header(HttpHeaders.AUTHORIZATION,
                "Bearer " + TestJwt.token(UUID.randomUUID(), "CLIENT"))).andExpect(status().isNotFound());

        simulate(user, id, "DECLINE", UUID.randomUUID()).andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.decline_reason").value("SIMULATED_DECLINE"));
        assertThat(awaitPaymentEvent("PaymentDeclined", id)).containsEntry("event_type", "PaymentDeclined");
    }
}
