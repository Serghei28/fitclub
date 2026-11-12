package com.fitclub.e2e.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Минимальный HTTP-клиент к API Gateway. Тесты работают как внешний клиент: только публичный /api,
 * JSON и заголовки Authorization / Idempotency-Key. Токены в вывод не попадают.
 */
public final class Api {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public Api(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** Ответ: статус и тело (MissingNode, если тела нет). */
    public record Response(int status, JsonNode body, String requestId) {

        public String code() {
            return body.path("code").asString("");
        }

        public String text(String field) {
            return body.path(field).asString(null);
        }

        public UUID id() {
            return UUID.fromString(body.path("id").asString());
        }

        @Override
        public String toString() {
            // Тело ответа без токенов: в отчётах о падении теста видны статус, код ошибки и request id
            String shown = body.has("access_token") ? "{token response}" : body.toString();
            return "HTTP " + status + " request_id=" + requestId + " " + shown;
        }
    }

    public Response get(String path, String token) {
        return send("GET", path, token, null, null);
    }

    public Response post(String path, String token, Object body) {
        return send("POST", path, token, body, null);
    }

    public Response post(String path, String token, Object body, String idempotencyKey) {
        return send("POST", path, token, body, idempotencyKey);
    }

    public Response patch(String path, String token, Object body) {
        return send("PATCH", path, token, body, null);
    }

    public Response send(String method, String path, String token, Object body, String idempotencyKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        } else {
            request.method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(""));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = response.body() == null || response.body().isBlank()
                    ? MissingNode.getInstance() : JSON.readTree(response.body());
            return new Response(response.statusCode(), node,
                    response.headers().firstValue("X-Request-ID").orElse("-"));
        } catch (IOException e) {
            // Сетевой сбой — как ответ со статусом 0: ожидания с Awaitility просто повторят попытку
            return new Response(0, MissingNode.getInstance(), "-");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
