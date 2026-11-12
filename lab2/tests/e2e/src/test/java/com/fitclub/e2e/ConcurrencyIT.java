package com.fitclub.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import com.fitclub.e2e.support.Api.Response;
import com.fitclub.e2e.support.Fitclub;
import com.fitclub.e2e.support.Fitclub.User;

/** Параллельные запросы через Gateway к общей БД Booking: ресурсы не превышаются. */
class ConcurrencyIT {

    private final Fitclub fc = new Fitclub();

    @Test
    @DisplayName("Пять клиентов одновременно на последнее место — подтверждена ровно одна запись")
    void lastPlace() throws Exception {
        User trainer = fc.trainer("race-trainer");
        UUID planId = fc.plan("E2E безлимит", null, 50000);
        UUID sessionId = fc.publishedSession(trainer, "E2E последнее место", 60, 1);
        List<User> clients = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            clients.add(fc.clientWithMembership("racer" + i, planId));
        }

        List<Response> results = race(clients.stream().map(c -> (Call) () -> fc.book(c, sessionId, Fitclub.key())).toList());

        assertThat(results).filteredOn(r -> r.status() == 201).as("%s", results).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == 409).allMatch(r -> r.code().equals("SESSION_FULL"));
        JsonNode availability = fc.availability(sessionId);
        assertThat(availability.path("booked").asInt()).isEqualTo(1);
        assertThat(availability.path("available").asInt()).isZero();
    }

    @Test
    @DisplayName("Одно оставшееся посещение и два занятия одновременно — списано не больше лимита")
    void lastVisit() throws Exception {
        User trainer = fc.trainer("visit-trainer");
        UUID planId = fc.plan("E2E 1 посещение", 1, 9000);
        UUID first = fc.publishedSession(trainer, "E2E первое", 70, 5);
        UUID second = fc.publishedSession(trainer, "E2E второе", 72, 5);
        User client = fc.clientWithMembership("one-visit", planId);

        List<Response> results = race(List.of(
                () -> fc.book(client, first, Fitclub.key()),
                () -> fc.book(client, second, Fitclub.key())));

        assertThat(results).filteredOn(r -> r.status() == 201).as("%s", results).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() != 201).singleElement()
                .extracting(Response::code).isEqualTo("VISIT_LIMIT_REACHED");
        JsonNode usage = fc.usage(client);
        assertThat(usage.path("used").asInt()).isEqualTo(1);
        assertThat(usage.path("remaining").asInt()).isZero();
    }

    interface Call {
        Response run();
    }

    /** Все запросы стартуют одновременно по общему сигналу. */
    private static List<Response> race(List<Call> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Response>> futures = new ArrayList<>();
            for (Call call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.run();
                }));
            }
            start.countDown();
            List<Response> results = new ArrayList<>();
            for (Future<Response> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
