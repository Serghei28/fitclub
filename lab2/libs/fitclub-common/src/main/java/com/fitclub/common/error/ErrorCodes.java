package com.fitclub.common.error;

/**
 * Технические коды ошибок, общие для всех сервисов.
 * Бизнес-коды (SESSION_FULL, VISIT_LIMIT_REACHED, ...) объявляет сам сервис-владелец правила.
 */
public final class ErrorCodes {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
    public static final String INVALID_TOKEN = "INVALID_TOKEN";
    public static final String INVALID_SERVICE_CREDENTIAL = "INVALID_SERVICE_CREDENTIAL";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";
    public static final String DEPENDENCY_BAD_RESPONSE = "DEPENDENCY_BAD_RESPONSE";
    public static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";
    public static final String DEPENDENCY_TIMEOUT = "DEPENDENCY_TIMEOUT";
    public static final String DATABASE_UNAVAILABLE = "DATABASE_UNAVAILABLE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
    }
}
