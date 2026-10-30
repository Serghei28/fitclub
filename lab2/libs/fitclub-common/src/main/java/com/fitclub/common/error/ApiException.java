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
