package com.fitclub.common.error;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.fitclub.common.web.RequestIds;

/**
 * Тело ошибки в формате application/problem+json (RFC 9457) с расширениями FitClub:
 * code, request_id и field_errors. Stack trace, SQL и внутренние адреса сюда не попадают.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("field_errors") List<FieldError> fieldErrors) {

    static final String TYPE_PREFIX = "https://fitclub.local/problems/";

    public record FieldError(String field, String message) {
    }

    public static ApiProblem of(int status, String code, String title, String detail, String instance) {
        return of(status, code, title, detail, instance, null);
    }

    public static ApiProblem of(int status, String code, String title, String detail, String instance,
            List<FieldError> fieldErrors) {
        String type = TYPE_PREFIX + code.toLowerCase(Locale.ROOT).replace('_', '-');
        return new ApiProblem(type, title, status, detail, instance, code,
                RequestIds.current().orElse(null), fieldErrors);
    }
}
