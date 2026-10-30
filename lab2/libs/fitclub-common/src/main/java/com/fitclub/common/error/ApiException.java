package com.fitclub.common.error;

import org.springframework.http.HttpStatus;

/**
 * Управляемая ошибка API со стабильным машинным кодом.
 * Бизнес-сервисы бросают её (или наследников) из application-слоя;
 * GlobalExceptionHandler превращает её в application/problem+json.
 * Сообщение detail предназначено пользователю и не должно содержать внутренних подробностей.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;

    public ApiException(HttpStatus status, String code, String title, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "Ресурс не найден", detail);
    }

    public static ApiException conflict(String code, String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, title, detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED, "Доступ запрещён", detail);
    }

    /** 503: зависимый сервис не принимает соединение. */
    public static ApiException dependencyUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.DEPENDENCY_UNAVAILABLE,
                "Сервис временно недоступен", "Связанный сервис временно недоступен. Повторите попытку позже.");
    }

    /** 504: зависимый сервис не ответил за отведённое время. */
    public static ApiException dependencyTimeout() {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, ErrorCodes.DEPENDENCY_TIMEOUT,
                "Сервис не ответил вовремя", "Связанный сервис не ответил вовремя. Повторите попытку позже.");
    }

    /** 502: зависимый сервис ответил ошибкой или некорректными данными. */
    public static ApiException dependencyBadResponse() {
        return new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.DEPENDENCY_BAD_RESPONSE,
                "Некорректный ответ сервиса", "Связанный сервис вернул некорректный ответ. Повторите попытку позже.");
    }

    public boolean isTransientDependencyFailure() {
        return status == HttpStatus.SERVICE_UNAVAILABLE || status == HttpStatus.GATEWAY_TIMEOUT
                || status == HttpStatus.BAD_GATEWAY;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return getMessage();
    }
}
