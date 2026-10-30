package com.fitclub.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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

import com.fitclub.common.idempotency.IdempotencyStore.Outcome;
import com.fitclub.membership.application.OrderView;
import com.fitclub.membership.application.PlaceOrderUseCase;
import com.fitclub.testsupport.PostgresTestcontainers;
import com.fitclub.testsupport.TestJwt;

/** membership-service на настоящем PostgreSQL: тарифы, заказы, Idempotency-Key, гонки и внутренний API. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestcontainers.class)
class MembershipApiIT {

    static final String PAYMENT_TOKEN = "test-service-token-payment-to-membership-0123456789abcdef";
    static final String BOOKING_TOKEN = "test-service-token-booking-to-membership-0123456789abcdef";
    static final String ADMIN = TestJwt.token(UUID.randomUUID(), "ADMIN");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlaceOrderUseCase placeOrder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fitclub.security.jwt-public-key", TestJwt::publicKeyLocation);
    }

    static String clientToken(UUID userId) {
        return TestJwt.token(userId, "CLIENT");
    }

    ResultActions postJson(String path, String token, String json, UUID idempotencyKey) throws Exception {
        var request = post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey.toString());
        }
        return mvc.perform(request);
    }

    static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    String createPlan(Integer visitLimit, long priceMinor) throws Exception {
        String limit = visitLimit == null ? "" : ",\"visit_limit\":" + visitLimit;
        return JsonPath.read(body(postJson("/api/plans", ADMIN, """
                {"name":"Тариф %s","duration_days":30,"price_minor":%d%s}
                """.formatted(UUID.randomUUID(), priceMinor, limit), null).andExpect(status().isCreated())), "$.id");
    }

    ResultActions order(UUID user, String planId, UUID key) throws Exception {
        return postJson("/api/membership-orders", clientToken(user), "{\"plan_id\":\"" + planId + "\"}", key);
    }

    /** Абонемент через JDBC: активация по событию оплаты появится на шаге 7. */
    void insertMembership(UUID user, Instant start, Instant end) {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String planId = jdbc.queryForObject("select id::text from plans limit 1", String.class);
        jdbc.update("""
                insert into membership_orders (id, user_id, plan_id, plan_name, duration_days, visit_limit, price_minor,
                    currency, status, payment_id, paid_at, created_at, updated_at)
                values (?, ?, ?::uuid, 'Тест', 30, 8, 45000, 'MDL', 'PAID', ?, ?, now(), now())
                """, orderId, user, planId, paymentId, Timestamp.from(start));
        jdbc.update("""
                insert into memberships (id, user_id, order_id, payment_id, plan_name, visit_limit, starts_at, ends_at,
                    created_at) values (?, ?, ?, ?, 'Тест', 8, ?, ?, now())
                """, UUID.randomUUID(), user, orderId, paymentId, Timestamp.from(start), Timestamp.from(end));
    }

    @Nested
    class Plans {

        @Test
        void adminCreatesFiniteAndUnlimitedPlans() throws Exception {
            postJson("/api/plans", ADMIN, "{\"name\":\"Безлимит " + UUID.randomUUID()
                    + "\",\"duration_days\":30,\"price_minor\":90000}", null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.unlimited").value(true))
                    // для безлимита visit_limit = null, а не отсутствует
                    .andExpect(jsonPath("$.visit_limit").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.currency").value("MDL"));
        }

        @Test
        void invalidPlanAndNonAdminAreRejected() throws Exception {
            postJson("/api/plans", ADMIN, "{\"name\":\"X\",\"duration_days\":30,\"visit_limit\":0,\"price_minor\":1}",
                    null).andExpect(status().isBadRequest());
            postJson("/api/plans", clientToken(UUID.randomUUID()),
                    "{\"name\":\"X\",\"duration_days\":30,\"price_minor\":1}", null).andExpect(status().isForbidden());
        }

        @Test
        void deactivatedPlanCannotBeOrdered() throws Exception {
            String planId = createPlan(8, 45000);
            mvc.perform(patch("/api/plans/" + planId).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                    .andExpect(jsonPath("$.active").value(false));
            order(UUID.randomUUID(), planId, UUID.randomUUID())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PLAN_NOT_AVAILABLE"));
        }
    }

    @Nested
    class Orders {

        @Test
        void orderUsesServerPriceAndIgnoresClientPrice() throws Exception {
            String planId = createPlan(8, 45000);
            postJson("/api/membership-orders", clientToken(UUID.randomUUID()),
                    "{\"plan_id\":\"" + planId + "\",\"price_minor\":1,\"status\":\"PAID\"}", UUID.randomUUID())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.price_minor").value(45000))
                    .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));
        }

        @Test
        void laterPlanChangeDoesNotChangeExistingOrder() throws Exception {
            String planId = createPlan(8, 45000);
            UUID user = UUID.randomUUID();
            String orderId = JsonPath.read(body(order(user, planId, UUID.randomUUID())), "$.id");
            mvc.perform(patch("/api/plans/" + planId).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"price_minor\":99900,\"visit_limit\":20}"))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/membership-orders/" + orderId).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + clientToken(user)))
                    .andExpect(jsonPath("$.price_minor").value(45000))
                    .andExpect(jsonPath("$.visit_limit").value(8));
        }

        @Test
        void sameIdempotencyKeyReplaysAndDifferentBodyConflicts() throws Exception {
            String planA = createPlan(8, 45000);
            String planB = createPlan(null, 90000);
            UUID user = UUID.randomUUID();
            UUID key = UUID.randomUUID();
            String first = JsonPath.read(body(order(user, planA, key).andExpect(status().isCreated())), "$.id");

            order(user, planA, key)
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.id").value(first));
            order(user, planB, key)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        }

        @Test
        void idempotencyKeyIsRequired() throws Exception {
            order(UUID.randomUUID(), createPlan(8, 45000), null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }

        @Test
        void pageRefreshReturnsPendingOrderAndOtherPlanConflicts() throws Exception {
            String planA = createPlan(8, 45000);
            String planB = createPlan(null, 90000);
            UUID user = UUID.randomUUID();
            String first = JsonPath.read(body(order(user, planA, UUID.randomUUID())), "$.id");

            order(user, planA, UUID.randomUUID())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(first));
            order(user, planB, UUID.randomUUID())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PENDING_ORDER_EXISTS"));
        }

        @Test
        void notEndedMembershipBlocksNewOrder() throws Exception {
            UUID user = UUID.randomUUID();
            String planId = createPlan(8, 45000);
            insertMembership(user, Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(29)));
            order(user, planId, UUID.randomUUID())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ACTIVE_MEMBERSHIP_EXISTS"));
        }

        @Test
        void foreignOrderIsNotFoundButAdminSeesIt() throws Exception {
            String orderId = JsonPath.read(body(order(UUID.randomUUID(), createPlan(8, 45000), UUID.randomUUID())),
                    "$.id");
            mvc.perform(get("/api/membership-orders/" + orderId).header(HttpHeaders.AUTHORIZATION,
                    "Bearer " + clientToken(UUID.randomUUID()))).andExpect(status().isNotFound());
            mvc.perform(get("/api/membership-orders/" + orderId).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class Concurrency {

        <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            pool.shutdown();
            return results;
        }

        @Test
        void parallelOrdersOfOneUserWithDifferentKeysCreateExactlyOneOrder() throws Exception {
            UUID user = UUID.randomUUID();
            UUID plan = UUID.fromString(createPlan(8, 45000));
            List<Outcome<OrderView>> results = runConcurrently(8,
                    () -> placeOrder.place(user, UUID.randomUUID(), new PlaceOrderUseCase.Request(plan)));

            Set<UUID> ids = new HashSet<>();
            results.forEach(r -> ids.add(r.body().id()));
            assertThat(ids).hasSize(1);
            assertThat(results.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from membership_orders where user_id = ?", Integer.class,
                    user)).isEqualTo(1);
        }

        @Test
        void parallelRetriesWithSameKeyExecuteOnce() throws Exception {
            UUID user = UUID.randomUUID();
            UUID plan = UUID.fromString(createPlan(8, 45000));
            UUID key = UUID.randomUUID();
            List<Outcome<OrderView>> results = runConcurrently(6,
                    () -> placeOrder.place(user, key, new PlaceOrderUseCase.Request(plan)));

            assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
            assertThat(results.stream().map(r -> r.body().id()).distinct().count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from idempotency_records where owner_id = ?", Integer.class,
                    user)).isEqualTo(1);
        }
    }

    @Nested
    class InternalApi {

        @Test
        void paymentDetailsOnlyForPaymentService() throws Exception {
            UUID user = UUID.randomUUID();
            String orderId = JsonPath.read(body(order(user, createPlan(8, 45000), UUID.randomUUID())), "$.id");
            String path = "/internal/membership-orders/" + orderId + "/payment-details";

            mvc.perform(get(path).header("X-Service-Token", PAYMENT_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user_id").value(user.toString()))
                    .andExpect(jsonPath("$.amount_minor").value(45000))
                    .andExpect(jsonPath("$.currency").value("MDL"))
                    .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));
            mvc.perform(get(path).header("X-Service-Token", BOOKING_TOKEN)).andExpect(status().isForbidden());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN)).andExpect(status().isUnauthorized());
            mvc.perform(get("/internal/membership-orders/" + UUID.randomUUID() + "/payment-details")
                    .header("X-Service-Token", PAYMENT_TOKEN)).andExpect(status().isNotFound());
        }

        @Test
        void eligibilityRespectsHalfOpenPeriod() throws Exception {
            createPlan(8, 45000);
            UUID user = UUID.randomUUID();
            Instant start = Instant.parse("2026-12-01T00:00:00Z");
            Instant end = Instant.parse("2026-12-31T00:00:00Z");
            insertMembership(user, start, end);

            eligibility(user, start).andExpect(jsonPath("$.eligible").value(true))
                    .andExpect(jsonPath("$.visit_limit").value(8))
                    .andExpect(jsonPath("$.membership_id").exists());
            eligibility(user, end.minusSeconds(1)).andExpect(jsonPath("$.eligible").value(true));
            eligibility(user, end).andExpect(jsonPath("$.eligible").value(false))
                    .andExpect(jsonPath("$.reason").value("NO_VALID_MEMBERSHIP"));
            eligibility(UUID.randomUUID(), start).andExpect(jsonPath("$.eligible").value(false));
            mvc.perform(get("/internal/memberships/eligibility").param("user_id", user.toString())
                    .param("at", start.toString()).header("X-Service-Token", PAYMENT_TOKEN))
                    .andExpect(status().isForbidden());
        }

        ResultActions eligibility(UUID user, Instant at) throws Exception {
            return mvc.perform(get("/internal/memberships/eligibility").param("user_id", user.toString())
                    .param("at", at.toString()).header("X-Service-Token", BOOKING_TOKEN)).andExpect(status().isOk());
        }

        @Test
        void myMembershipsShowTimeBasedStatus() throws Exception {
            createPlan(8, 45000);
            UUID user = UUID.randomUUID();
            insertMembership(user, Instant.now().minus(Duration.ofDays(40)), Instant.now().minus(Duration.ofDays(10)));
            insertMembership(user, Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(29)));
            mvc.perform(get("/api/memberships/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                    .andExpect(jsonPath("$[1].status").value("EXPIRED"));
        }
    }
}
