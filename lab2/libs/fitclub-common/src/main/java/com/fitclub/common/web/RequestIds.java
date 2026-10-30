package com.fitclub.common.web;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/** Правила X-Request-ID: допустимый формат, генерация и доступ к текущему значению через MDC. */
public final class RequestIds {

    public static final String HEADER = "X-Request-ID";
    public static final String MDC_KEY = "request_id";
    /** Атрибут запроса: нужен для error-dispatch, когда MDC исходного запроса уже очищен. */
    public static final String ATTRIBUTE = RequestIds.class.getName() + ".requestId";

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private RequestIds() {
    }

    /** Принимает входящий идентификатор только в допустимом формате, иначе генерирует новый. */
    public static String acceptOrGenerate(String incoming) {
        if (incoming != null && VALID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }
}
