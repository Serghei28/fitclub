package com.fitclub.booking;

import static com.fitclub.booking.BookingTestSupport.awaitInventory;
import static com.fitclub.booking.BookingTestSupport.cancelSession;
import static com.fitclub.booking.BookingTestSupport.membership;
import static com.fitclub.booking.BookingTestSupport.publishSession;
import static com.fitclub.booking.BookingTestSupport.stubMembership;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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

import com.fitclub.booking.application.CancelBookingUseCase;
import com.fitclub.booking.application.CreateBookingUseCase;
import com.fitclub.booking.application.SessionProjectionHandler;
import com.fitclub.common.error.DomainException;
import com.fitclub.testsupport.PostgresTestcontainers;
import com.fitclub.testsupport.RabbitTestcontainers;
import com.fitclub.testsupport.TestJwt;

/**
 * booking-service на настоящих PostgreSQL и RabbitMQ, membership-service — WireMock.
 * Конкурентные тесты запускают действительно параллельные транзакции к общей БД и проверяют итоговые инварианты.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestcontainers.class, BookingTestSupport.ClockConfig.class})
class BookingIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    BookingTestSupport.MutableClock clock;

    @Autowired
    CreateBookingUseCase createBooking;

    @Autowired
    CancelBookingUseCase cancelBooking;

    @Autowired
    SessionProjectionHandler projection;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fitclub.security.jwt-public-key", TestJwt::publicKeyLocation);
        registry.add("fitclub.booking.membership.base-url", membership::baseUrl);
        RabbitTestcontainers.register(registry);
    }

    @BeforeEach
    void reset() {
        BookingTestSupport.stubNoMembershipByDefault();
        clock.reset();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    static Instant inDays(int days) {
        return Instant.now().plus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS);
    }

    UUID openSession(int capacity, Instant startsAt) {
        return openSession(capacity, startsAt, UUID.randomUUID());
    }

    UUID openSession(int capacity, Instant startsAt, UUID trainer) {
        UUID id = UUID.randomUUID();
        publishSession(rabbit, id, trainer, capacity, startsAt, 1);
        awaitInventory(jdbc, id, "OPEN", 1);
        return id;
    }

    ResultActions book(UUID user, UUID sessionId, UUID key) throws Exception {
        return mvc.perform(post("/api/bookings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.builder(user, "CLIENT").name("Мария").sign())
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"session_id\":\"" + sessionId + "\"}"));
    }

    ResultActions cancel(UUID user, String bookingId) throws Exception {
        return mvc.perform(post("/api/bookings/" + bookingId + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.token(user, "CLIENT")));
    }

    ResultActions getAs(String path, String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    int booked(UUID sessionId) {
        return jdbc.queryForObject("select booked_count from session_inventory where session_id = ?", Integer.class,
                sessionId);
    }

    int used(UUID membershipId) {
        return jdbc.queryForObject("select used_count from membership_usage where membership_id = ?", Integer.class,
                membershipId);
    }

    @Nested
    class Rules {

        @Test
        void sessionWithoutSnapshotIsNotReady() throws Exception {
            book(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_NOT_READY"));
        }

        @Test
        void bookingRequiresMembershipValidAtSessionStart() throws Exception {
            Instant start = inDays(3);
            UUID session = openSession(5, start);
            UUID user = UUID.randomUUID();
            book(user, session, UUID.randomUUID())
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBERSHIP_NOT_VALID"));
            membership.verify(getRequestedFor(urlPathEqualTo("/internal/memberships/eligibility"))
                    .withQueryParam("user_id", equalTo(user.toString()))
                    .withQueryParam("at", equalTo(start.toString())));
        }

        @Test
        void successfulBookingTakesSeatAndVisitAndEmitsEvent() throws Exception {
            UUID session = openSession(5, inDays(3));
            UUID user = UUID.randomUUID();
            UUID membershipId = stubMembership(user, 8);

            String id = JsonPath.read(body(book(user, session, UUID.randomUUID())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.cancellable").value(true))), "$.id");

            assertThat(booked(session)).isEqualTo(1);
            assertThat(used(membershipId)).isEqualTo(1);
            getAs("/api/availability?session_id=" + session + "&session_id=" + UUID.randomUUID(),
                    TestJwt.token(user, "CLIENT"))
                    .andExpect(jsonPath("$[0].available").value(4))
                    .andExpect(jsonPath("$[1].state").value("NOT_READY"));
            getAs("/api/bookings/usage/me", TestJwt.token(user, "CLIENT"))
                    .andExpect(jsonPath("$[0].used").value(1)).andExpect(jsonPath("$[0].remaining").value(7));
            assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?::uuid "
                    + "and event_type = 'BookingConfirmed' and aggregate_version = 1", Integer.class, id)).isEqualTo(1);
        }

        @Test
        void repeatBookingOfSameSessionIsRejected() throws Exception {
            UUID session = openSession(5, inDays(3));
            UUID user = UUID.randomUUID();
            stubMembership(user, 8);
            book(user, session, UUID.randomUUID()).andExpect(status().isCreated());
            book(user, session, UUID.randomUUID())
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_BOOKED"));
        }

        @Test
        void fullSessionAndExhaustedLimit() throws Exception {
            UUID single = openSession(1, inDays(3));
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            stubMembership(first, 1);
            stubMembership(second, 8);
            book(first, single, UUID.randomUUID()).andExpect(status().isCreated());
            book(second, single, UUID.randomUUID())
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_FULL"));

            UUID other = openSession(5, inDays(4));
            book(first, other, UUID.randomUUID())
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISIT_LIMIT_REACHED"));
        }

        @Test
        void unlimitedMembershipHasNoVisitLimit() throws Exception {
            UUID user = UUID.randomUUID();
            stubMembership(user, null);
            for (int i = 0; i < 3; i++) {
                book(user, openSession(5, inDays(3 + i)), UUID.randomUUID()).andExpect(status().isCreated());
            }
            getAs("/api/bookings/usage/me", TestJwt.token(user, "CLIENT"))
                    .andExpect(jsonPath("$[0].unlimited").value(true)).andExpect(jsonPath("$[0].used").value(3));
        }

        @Test
        void startedSessionCannotBeBooked() throws Exception {
            UUID session = openSession(5, Instant.now().minusSeconds(60));
            UUID user = UUID.randomUUID();
            stubMembership(user, 8);
            book(user, session, UUID.randomUUID())
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_ALREADY_STARTED"));
        }

        @Test
        void membershipOutageIsDependencyErrorNotPermission() throws Exception {
            UUID session = openSession(5, inDays(3));
            membership.stubFor(get(urlPathEqualTo("/internal/memberships/eligibility"))
                    .willReturn(aResponse().withFixedDelay(1500)));
            book(UUID.randomUUID(), session, UUID.randomUUID())
                    .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("DEPENDENCY_TIMEOUT"));
            assertThat(booked(session)).isZero();
        }

        @Test
        void oldIdempotencyKeyReplaysOriginalEvenAfterCancellation() throws Exception {
            UUID session = openSession(5, inDays(3));
            UUID user = UUID.randomUUID();
            stubMembership(user, 8);
            UUID key = UUID.randomUUID();
            String id = JsonPath.read(body(book(user, session, key).andExpect(status().isCreated())), "$.id");
            cancel(user, id).andExpect(jsonPath("$.status").value("CANCELLED"));

            book(user, session, key)
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.id").value(id));
            assertThat(jdbc.queryForObject("select count(*) from bookings where user_id = ?", Integer.class, user))
                    .isEqualTo(1);
        }
    }

    @Nested
    class Cancellation {

        @Test
        void cancellationWindowBoundaryAndSingleRefund() throws Exception {
            Instant start = inDays(3);
            UUID session = openSession(5, start);
            UUID user = UUID.randomUUID();
            UUID membershipId = stubMembership(user, 8);
            String onTime = JsonPath.read(body(book(user, session, UUID.randomUUID())), "$.id");
            UUID session2 = openSession(5, start);
            String late = JsonPath.read(body(book(user, session2, UUID.randomUUID())), "$.id");
            assertThat(used(membershipId)).isEqualTo(2);

            Instant deadline = start.minus(Duration.ofMinutes(120));
            clock.set(deadline);
            cancel(user, onTime).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
            cancel(user, onTime).andExpect(status().isOk());
            assertThat(used(membershipId)).isEqualTo(1);
            assertThat(booked(session)).isZero();

            clock.set(deadline.plusSeconds(1));
            cancel(user, late)
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCELLATION_WINDOW_CLOSED"));
            assertThat(used(membershipId)).isEqualTo(1);
        }

        @Test
        void foreignBookingCannotBeCancelledOrSeen() throws Exception {
            UUID session = openSession(5, inDays(3));
            UUID user = UUID.randomUUID();
            stubMembership(user, 8);
            String id = JsonPath.read(body(book(user, session, UUID.randomUUID())), "$.id");
            cancel(UUID.randomUUID(), id).andExpect(status().isNotFound());
            getAs("/api/bookings/me", TestJwt.token(UUID.randomUUID(), "CLIENT"))
                    .andExpect(jsonPath("$.total_elements").value(0));
        }
    }

    @Nested
    class Participants {

        @Test
        void onlyAssignedTrainerAndAdminSeeParticipants() throws Exception {
            UUID trainer = UUID.randomUUID();
            UUID session = openSession(5, inDays(3), trainer);
            UUID user = UUID.randomUUID();
            stubMembership(user, 8);
            book(user, session, UUID.randomUUID()).andExpect(status().isCreated());
            String path = "/api/sessions/" + session + "/bookings";

            getAs(path, TestJwt.token(trainer, "TRAINER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.participants[0].display_name").value("Мария"))
                    .andExpect(jsonPath("$.booked").value(1));
            getAs(path, TestJwt.token(UUID.randomUUID(), "TRAINER")).andExpect(status().isForbidden());
            getAs(path, TestJwt.token(user, "CLIENT")).andExpect(status().isForbidden());
            getAs(path, TestJwt.token(UUID.randomUUID(), "ADMIN")).andExpect(status().isOk());
        }
    }

    @Nested
    class Projection {

        @Test
        void sessionCancellationReleasesEverythingAndStalePublishCannotReopen() throws Exception {
            UUID session = openSession(5, inDays(3));
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID ma = stubMembership(a, 8);
            UUID mb = stubMembership(b, null);
            book(a, session, UUID.randomUUID()).andExpect(status().isCreated());
            book(b, session, UUID.randomUUID()).andExpect(status().isCreated());

            cancelSession(rabbit, session, 2);
            awaitInventory(jdbc, session, "CANCELLED", 2);
            assertThat(booked(session)).isZero();
            assertThat(used(ma)).isZero();
            assertThat(used(mb)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from bookings where session_id = ? and status = 'CANCELLED' "
                    + "and cancel_reason = 'SESSION_CANCELLED'", Integer.class, session)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'BookingCancelled' "
                    + "and envelope -> 'payload' ->> 'session_id' = ?", Integer.class, session.toString())).isEqualTo(2);

            // Опоздавшее (повторное) SessionPublished со старой версией не открывает запись снова
            publishSession(rabbit, session, UUID.randomUUID(), 5, inDays(3), 1);
            UUID c = UUID.randomUUID();
            stubMembership(c, 8);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> book(c, session, UUID.randomUUID())
                    .andExpect(jsonPath("$.code").value("SESSION_CANCELLED")));
        }

        @Test
        void cancellationArrivingBeforePublicationLeavesTombstone() throws Exception {
            UUID session = UUID.randomUUID();
            cancelSession(rabbit, session, 2);
            awaitInventory(jdbc, session, "CANCELLED", 2);
            publishSession(rabbit, session, UUID.randomUUID(), 5, inDays(3), 1);
            Thread.sleep(1000);
            assertThat(jdbc.queryForObject("select status from session_inventory where session_id = ?", String.class,
                    session)).isEqualTo("CANCELLED");
        }
    }

    @Nested
    class Concurrency {

        <T> List<T> parallel(List<Callable<T>> tasks) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
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

        String attempt(UUID user, UUID session) {
            try {
                createBooking.book(new CreateBookingUseCase.Booker(user, "Клиент"), UUID.randomUUID(),
                        new CreateBookingUseCase.Request(session));
                return "OK";
            } catch (DomainException e) {
                return e.code();
            }
        }

        @Test
        void twoClientsCompeteForLastSeatExactlyOneWins() throws Exception {
            UUID session = openSession(1, inDays(3));
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            stubMembership(a, 8);
            stubMembership(b, 8);

            List<String> results = parallel(List.of(() -> attempt(a, session), () -> attempt(b, session)));

            assertThat(results).containsExactlyInAnyOrder("OK", "SESSION_FULL");
            assertThat(booked(session)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from bookings where session_id = ? and status = 'CONFIRMED'",
                    Integer.class, session)).isEqualTo(1);
        }

        @Test
        void tenClientsForThreeSeats() throws Exception {
            UUID session = openSession(3, inDays(3));
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                UUID user = UUID.randomUUID();
                stubMembership(user, 8);
                tasks.add(() -> attempt(user, session));
            }
            List<String> results = parallel(tasks);
            assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(3);
            assertThat(results.stream().filter("SESSION_FULL"::equals).count()).isEqualTo(7);
            assertThat(booked(session)).isEqualTo(3);
        }

        @Test
        void oneClientTwoSessionsOneVisitLeftExactlyOneWins() throws Exception {
            UUID s1 = openSession(5, inDays(3));
            UUID s2 = openSession(5, inDays(4));
            UUID user = UUID.randomUUID();
            UUID membershipId = stubMembership(user, 1);

            List<String> results = parallel(List.of(() -> attempt(user, s1), () -> attempt(user, s2)));

            assertThat(results).containsExactlyInAnyOrder("OK", "VISIT_LIMIT_REACHED");
            assertThat(used(membershipId)).isEqualTo(1);
            assertThat(booked(s1) + booked(s2)).isEqualTo(1);
        }

        @Test
        void userCancellationRacingSessionCancellationRefundsOnce() throws Exception {
            UUID session = openSession(5, inDays(3));
            UUID user = UUID.randomUUID();
            UUID membershipId = stubMembership(user, 8);
            UUID bookingId = createBooking.book(new CreateBookingUseCase.Booker(user, "Клиент"), UUID.randomUUID(),
                    new CreateBookingUseCase.Request(session)).body().id();

            parallel(List.<Callable<Object>>of(
                    () -> cancelBooking.cancelByUser(user, bookingId),
                    () -> {
                        projection.onSessionCancelled(session, 2, Instant.now());
                        return null;
                    }));

            assertThat(used(membershipId)).isZero();
            assertThat(booked(session)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? "
                    + "and event_type = 'BookingCancelled'", Integer.class, bookingId)).isEqualTo(1);
        }
    }
}
