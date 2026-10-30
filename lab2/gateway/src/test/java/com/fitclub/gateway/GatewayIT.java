package com.fitclub.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fitclub.testsupport.TestJwt;

/**
 * Настоящий Gateway на случайном порту; сервисы заменены WireMock-серверами.
 * schedule и booking — отдельные серверы (проверка порядка маршрутов /api/sessions),
 * notification указывает на закрытый порт (503), payment отвечает дольше таймаута (504).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayIT {

    static final WireMockServer generic = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer schedule = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer booking = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer slow = new WireMockServer(wireMockConfig().dynamicPort());
    static final int closedPort = freePort();

    static {
        generic.start();
        schedule.start();
        booking.start();
        slow.start();
    }

    @Value("${local.server.port}")
    int port;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fitclub.gateway.services.auth", generic::baseUrl);
        registry.add("fitclub.gateway.services.membership", generic::baseUrl);
        registry.add("fitclub.gateway.services.schedule", schedule::baseUrl);
        registry.add("fitclub.gateway.services.booking", booking::baseUrl);
        registry.add("fitclub.gateway.services.payment", slow::baseUrl);
        registry.add("fitclub.gateway.services.notification", () -> "http://localhost:" + closedPort);
        registry.add("fitclub.gateway.jwt.public-key", TestJwt::publicKeyLocation);
    }

    @AfterAll
    static void stopServers() {
        generic.stop();
        schedule.stop();
        booking.stop();
        slow.stop();
    }

    @BeforeEach
    void resetStubs() {
        for (WireMockServer server : new WireMockServer[] {generic, schedule, booking, slow}) {
            server.resetAll();
            server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
        }
        slow.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withFixedDelay(3000)));
    }

    HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
    }

    HttpRequest.Builder authorized(String path) {
        return request(path).header("Authorization", "Bearer " + TestJwt.token(UUID.randomUUID(), "CLIENT"));
    }

    static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/problem+json"));
        assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo(code);
        assertThat((String) JsonPath.read(response.body(), "$.request_id")).isNotBlank();
        assertThat(response.body()).doesNotContain("localhost:").doesNotContain("Exception");
    }

    @Nested
    class Routing {

        @Test
        void publicAuthRouteIsForwardedWithoutTokenAndWithoutServiceToken() throws Exception {
            HttpResponse<String> response = send(request("/api/auth/login")
                    .header("Content-Type", "application/json")
                    .header("X-Service-Token", "stolen-or-guessed-token")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"a@b.md\",\"password\":\"x\"}")));

            assertThat(response.statusCode()).isEqualTo(200);
            generic.verify(postRequestedFor(urlEqualTo("/api/auth/login"))
                    .withHeader("X-Service-Token", absent()));
        }

        @Test
        void participantsOfSessionGoToBookingButSessionGoesToSchedule() throws Exception {
            UUID sessionId = UUID.randomUUID();
            send(authorized("/api/sessions/" + sessionId + "/bookings").GET());
            send(authorized("/api/sessions/" + sessionId).GET());

            booking.verify(getRequestedFor(urlEqualTo("/api/sessions/" + sessionId + "/bookings")));
            schedule.verify(getRequestedFor(urlEqualTo("/api/sessions/" + sessionId)));
            schedule.verify(0, getRequestedFor(urlEqualTo("/api/sessions/" + sessionId + "/bookings")));
        }

        @Test
        void validUserTokenIsForwardedToService() throws Exception {
            String token = TestJwt.token(UUID.randomUUID(), "CLIENT");
            HttpResponse<String> response = send(request("/api/plans").header("Authorization", "Bearer " + token)
                    .GET());
            assertThat(response.statusCode()).isEqualTo(200);
            generic.verify(getRequestedFor(urlEqualTo("/api/plans"))
                    .withHeader("Authorization", equalTo("Bearer " + token)));
        }

        @Test
        void serviceErrorsPassThroughUnchanged() throws Exception {
            generic.stubFor(get(urlPathEqualTo("/api/membership-orders/42")).willReturn(aResponse().withStatus(409)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("{\"status\":409,\"code\":\"PENDING_ORDER_EXISTS\"}")));
            HttpResponse<String> response = send(authorized("/api/membership-orders/42").GET());
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body()).contains("PENDING_ORDER_EXISTS");
        }

        @Test
        void serviceOpenApiIsAvailableThroughGateway() throws Exception {
            HttpResponse<String> response = send(request("/docs/auth/v3/api-docs").GET());
            assertThat(response.statusCode()).isEqualTo(200);
            // X-Forwarded-Host нужен сервису, чтобы Swagger «Try it out» шёл на адрес Gateway, а не контейнера
            generic.verify(getRequestedFor(urlEqualTo("/v3/api-docs"))
                    .withHeader("X-Forwarded-Host", matching("localhost:" + port)));
        }

        @Test
        void unknownApiPathIsNotFoundProblem() throws Exception {
            assertProblem(send(authorized("/api/does-not-exist").GET()), 404, "RESOURCE_NOT_FOUND");
        }
    }

    @Nested
    class Security {

        @Test
        void protectedRouteWithoutTokenIsRejectedBeforeReachingService() throws Exception {
            assertProblem(send(request("/api/bookings/me").GET()), 401, "AUTHENTICATION_REQUIRED");
            booking.verify(0, anyRequestedFor(anyUrl()));
        }

        @Test
        void forgedAndExpiredTokensAreRejected() throws Exception {
            String forged = TestJwt.builder(UUID.randomUUID(), "ADMIN").signedByUntrustedKey().sign();
            String expired = TestJwt.builder(UUID.randomUUID(), "CLIENT").expiredAgo(Duration.ofMinutes(5)).sign();
            assertProblem(send(request("/api/plans").header("Authorization", "Bearer " + forged).GET()),
                    401, "INVALID_TOKEN");
            assertProblem(send(request("/api/plans").header("Authorization", "Bearer " + expired).GET()),
                    401, "INVALID_TOKEN");
            generic.verify(0, anyRequestedFor(anyUrl()));
        }

        @Test
        void internalRoutesAreNotPublished() throws Exception {
            assertProblem(send(authorized("/internal/memberships/eligibility")
                    .header("X-Service-Token", "anything").GET()), 404, "RESOURCE_NOT_FOUND");
            generic.verify(0, anyRequestedFor(anyUrl()));
        }

        @Test
        void pathTraversalTowardsInternalRoutesIsRejected() throws Exception {
            assertProblem(send(authorized("/api/plans/../../internal/memberships/eligibility").GET()),
                    400, "MALFORMED_REQUEST");
            assertProblem(send(authorized("/api/plans/%2e%2e/%2e%2e/internal/x").GET()), 400, "MALFORMED_REQUEST");
            assertProblem(send(authorized("/api/plans;x=1").GET()), 400, "MALFORMED_REQUEST");
            generic.verify(0, anyRequestedFor(anyUrl()));
        }
    }

    @Nested
    class RequestId {

        @Test
        void validRequestIdIsPassedToServiceAndReturned() throws Exception {
            HttpResponse<String> response = send(authorized("/api/plans").header("X-Request-ID", "ui-req-000123").GET());
            assertThat(response.headers().firstValue("X-Request-ID")).hasValue("ui-req-000123");
            generic.verify(getRequestedFor(urlEqualTo("/api/plans")).withHeader("X-Request-ID", equalTo("ui-req-000123")));
        }

        @Test
        void invalidRequestIdIsReplacedConsistently() throws Exception {
            HttpResponse<String> response = send(authorized("/api/plans").header("X-Request-ID", "<script>").GET());
            String generated = response.headers().firstValue("X-Request-ID").orElseThrow();
            assertThat(generated).matches("^[0-9a-f-]{36}$");
            generic.verify(getRequestedFor(urlEqualTo("/api/plans")).withHeader("X-Request-ID", equalTo(generated)));
        }
    }

    @Nested
    class DependencyFailures {

        @Test
        void unavailableServiceGivesControlled503() throws Exception {
            assertProblem(send(authorized("/api/notifications").GET()), 503, "DEPENDENCY_UNAVAILABLE");
        }

        @Test
        void slowServiceGivesControlled504() throws Exception {
            long started = System.nanoTime();
            assertProblem(send(authorized("/api/payments/1").GET()), 504, "DEPENDENCY_TIMEOUT");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2900));
        }

        @Test
        void gatewayHealthDoesNotDependOnServices() throws Exception {
            assertThat(send(request("/health").GET()).statusCode()).isEqualTo(200);
            assertThat(send(request("/ready").GET()).statusCode()).isEqualTo(200);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
