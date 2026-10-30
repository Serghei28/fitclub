package com.fitclub.gateway.error;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Тот же формат application/problem+json, что и у сервисов (ApiProblem в fitclub-common).
 * Gateway реактивный и не подключает servlet-модуль common, поэтому формат повторён здесь.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GatewayProblem(String type, String title, int status, String detail, String instance, String code,
        @JsonProperty("request_id") String requestId) {

    public static GatewayProblem of(int status, String code, String title, String detail, String instance,
            String requestId) {
        String type = "https://fitclub.local/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-');
        return new GatewayProblem(type, title, status, detail, instance, code, requestId);
    }
}
