package com.fitclub.schedule.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.fitclub.common.error.ApiException;
import com.fitclub.common.http.InternalServiceClient;
import com.fitclub.schedule.application.port.TrainerDirectory;
import com.fitclub.schedule.infrastructure.http.AuthTrainerDirectory;

/** HTTP-клиент внутреннего API auth-service против WireMock: контракт, таймауты, сбои и повтор GET. */
class AuthTrainerDirectoryTest {

    static final String TOKEN = "schedule-to-auth-token-0123456789abcdef";

    WireMockServer auth;
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void start() {
        auth = new WireMockServer(wireMockConfig().dynamicPort());
        auth.start();
    }

    @AfterEach
    void stop() {
        auth.stop();
        MDC.clear();
    }

    TrainerDirectory client(URI baseUrl, int attempts) {
        return new AuthTrainerDirectory(new InternalServiceClient("auth-service", baseUrl, TOKEN,
                Duration.ofMillis(500), Duration.ofMillis(700), attempts, Duration.ofMillis(50)));
    }

    String path() {
        return "/internal/users/" + userId + "/trainer-status";
    }

    @Test
    void sendsServiceTokenAndRequestIdAndParsesContract() {
        auth.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"user_id\":\"" + userId + "\",\"active\":true,\"trainer\":true,\"display_name\":\"Ольга\"}")));
        MDC.put("request_id", "req-schedule-0001");

        TrainerDirectory.TrainerStatus status = client(URI.create(auth.baseUrl()), 1).findTrainerStatus(userId)
                .orElseThrow();

        assertThat(status.eligible()).isTrue();
        assertThat(status.displayName()).isEqualTo("Ольга");
        auth.verify(getRequestedFor(urlEqualTo(path()))
                .withHeader("X-Service-Token", equalTo(TOKEN))
                .withHeader("X-Request-ID", equalTo("req-schedule-0001")));
    }

    @Test
    void unknownUserIsEmptyNotError() {
        auth.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withStatus(404)));
        assertThat(client(URI.create(auth.baseUrl()), 1).findTrainerStatus(userId)).isEmpty();
    }

    @Test
    void slowResponseBecomesTimeoutWithinBound() {
        auth.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withFixedDelay(2000).withBody("{}")));
        long started = System.nanoTime();
        assertThatThrownBy(() -> client(URI.create(auth.baseUrl()), 1).findTrainerStatus(userId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DEPENDENCY_TIMEOUT"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void serverErrorIsRetriedOnceThenBadResponse() {
        auth.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client(URI.create(auth.baseUrl()), 2).findTrainerStatus(userId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DEPENDENCY_BAD_RESPONSE"));
        auth.verify(2, getRequestedFor(urlEqualTo(path())));
    }

    @Test
    void rejectedServiceTokenIsNotRetried() {
        auth.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> client(URI.create(auth.baseUrl()), 2).findTrainerStatus(userId))
                .isInstanceOf(ApiException.class);
        auth.verify(1, getRequestedFor(urlEqualTo(path())));
    }

    @Test
    void malformedBodyIsBadResponse() {
        auth.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"user_id\":\"" + userId + "\"}")));
        assertThatThrownBy(() -> client(URI.create(auth.baseUrl()), 1).findTrainerStatus(userId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DEPENDENCY_BAD_RESPONSE"));
    }

    @Test
    void droppedConnectionIsNotTreatedAsSuccess() {
        auth.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThatThrownBy(() -> client(URI.create(auth.baseUrl()), 1).findTrainerStatus(userId))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void closedPortIsUnavailable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        assertThatThrownBy(() -> client(URI.create("http://localhost:" + port), 1).findTrainerStatus(userId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DEPENDENCY_UNAVAILABLE"));
    }
}
