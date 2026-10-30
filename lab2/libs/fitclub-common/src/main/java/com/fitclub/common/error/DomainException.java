package com.fitclub.common.error;

/**
 * Нарушение бизнес-правила, выброшенное из domain или application.
 * В отличие от ApiException, класс не зависит от Spring (нет HttpStatus), поэтому его можно
 * использовать в чистом доменном слое. HTTP-статус выбирает GlobalExceptionHandler по виду ошибки.
 */
public class DomainException extends RuntimeException {

    /** Вид нарушения → HTTP-статус: VALIDATION 400, UNAUTHORIZED 401, FORBIDDEN 403, NOT_FOUND 404, CONFLICT 409. */
    public enum Kind {
        VALIDATION, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT
    }

    private final Kind kind;
    private final String code;
    private final String title;
    private final String field;

    public DomainException(Kind kind, String code, String title, String detail) {
        this(kind, code, title, detail, null);
    }

    /** @param field поле запроса, к которому относится ошибка валидации (попадает в field_errors) */
    public DomainException(Kind kind, String code, String title, String detail, String field) {
        super(detail);
        this.kind = kind;
        this.code = code;
        this.title = title;
        this.field = field;
    }

    public Kind kind() {
        return kind;
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

    public String field() {
        return field;
    }
}
