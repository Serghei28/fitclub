package com.fitclub.common.error;

import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Единое преобразование исключений в application/problem+json.
 * Клиент получает стабильный code и понятный detail; подробности (stack trace) остаются только в логе
 * и связаны с ответом через request_id.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiProblem> handleApi(ApiException ex, HttpServletRequest request) {
        if (ex.status().is5xxServerError()) {
            log.warn("Request failed: {} ({})", ex.code(), ex.detail());
        } else {
            log.info("Request rejected: {}", ex.code());
        }
        return respond(ApiProblem.of(ex.status().value(), ex.code(), ex.title(), ex.detail(), request.getRequestURI()));
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiProblem> handleDomain(DomainException ex, HttpServletRequest request) {
        int status = switch (ex.kind()) {
            case VALIDATION -> 400;
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
        };
        log.info("Business rule rejected request: {}", ex.code());
        List<ApiProblem.FieldError> fieldErrors = ex.field() == null
                ? null
                : List.of(new ApiProblem.FieldError(ex.field(), ex.detail()));
        return respond(ApiProblem.of(status, ex.code(), ex.title(), ex.detail(), request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiProblem.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ApiProblem.FieldError(toSnakeCase(e.getField()), e.getDefaultMessage()))
                .toList();
        return validation(errors, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiProblem> handleParameterValidation(HandlerMethodValidationException ex,
            HttpServletRequest request) {
        List<ApiProblem.FieldError> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ApiProblem.FieldError(
                                toSnakeCase(result.getMethodParameter().getParameterName()),
                                error.getDefaultMessage())))
                .toList();
        return validation(errors, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiProblem> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiProblem.FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new ApiProblem.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return validation(errors, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiProblem> handleMalformed(Exception ex, HttpServletRequest request) {
        String detail = switch (ex) {
            case MissingRequestHeaderException e -> "Отсутствует обязательный заголовок " + e.getHeaderName() + ".";
            case MissingServletRequestParameterException e ->
                    "Отсутствует обязательный параметр " + e.getParameterName() + ".";
            case MethodArgumentTypeMismatchException e -> "Некорректное значение параметра " + e.getName() + ".";
            default -> "Тело запроса не удалось прочитать: проверьте JSON и типы полей.";
        };
        return respond(ApiProblem.of(400, ErrorCodes.MALFORMED_REQUEST, "Некорректный запрос", detail,
                request.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiProblem> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return respond(ApiProblem.of(404, ErrorCodes.RESOURCE_NOT_FOUND, "Ресурс не найден",
                "Запрошенный адрес не существует.", request.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiProblem> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return respond(ApiProblem.of(405, ErrorCodes.METHOD_NOT_ALLOWED, "Метод не поддерживается",
                "Метод " + ex.getMethod() + " не поддерживается для этого адреса.", request.getRequestURI()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiProblem> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return respond(ApiProblem.of(415, ErrorCodes.UNSUPPORTED_MEDIA_TYPE, "Неподдерживаемый формат",
                "Используйте Content-Type: application/json.", request.getRequestURI()));
    }

    /** Отказ method security (@PreAuthorize) внутри уже аутентифицированного запроса. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiProblem> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.info("Access denied by method security");
        return respond(ApiProblem.of(403, ErrorCodes.ACCESS_DENIED, "Доступ запрещён",
                "Недостаточно прав для этого действия.", request.getRequestURI()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiProblem> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        log.info("Optimistic lock conflict");
        return respond(ApiProblem.of(409, ErrorCodes.CONCURRENT_MODIFICATION, "Конфликт изменений",
                "Ресурс был изменён параллельно. Обновите данные и повторите действие.", request.getRequestURI()));
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class,
            QueryTimeoutException.class})
    ResponseEntity<ApiProblem> handleDatabaseUnavailable(Exception ex, HttpServletRequest request) {
        withErrorCode(ErrorCodes.DATABASE_UNAVAILABLE, () -> log.error("Database unavailable", ex));
        return respond(ApiProblem.of(503, ErrorCodes.DATABASE_UNAVAILABLE, "Сервис временно недоступен",
                "Хранилище данных временно недоступно. Повторите попытку позже.", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiProblem> handleUnexpected(Exception ex, HttpServletRequest request) {
        withErrorCode(ErrorCodes.INTERNAL_ERROR, () -> log.error("Unexpected error", ex));
        return respond(ApiProblem.of(500, ErrorCodes.INTERNAL_ERROR, "Внутренняя ошибка",
                "Произошла непредвиденная ошибка. Сообщите request_id администратору.", request.getRequestURI()));
    }

    private static ResponseEntity<ApiProblem> validation(List<ApiProblem.FieldError> errors,
            HttpServletRequest request) {
        log.info("Validation failed for {} field(s)", errors.size());
        return respond(ApiProblem.of(400, ErrorCodes.VALIDATION_ERROR, "Ошибка валидации",
                "Запрос содержит некорректные поля.", request.getRequestURI(), errors));
    }

    private static ResponseEntity<ApiProblem> respond(ApiProblem problem) {
        return ResponseEntity.status(HttpStatus.valueOf(problem.status()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static void withErrorCode(String code, Runnable logging) {
        MDC.put("error_code", code);
        try {
            logging.run();
        } finally {
            MDC.remove("error_code");
        }
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return toSnakeCase(dot >= 0 ? path.substring(dot + 1) : path);
    }

    /** Имена полей в field_errors совпадают с JSON (snake_case), а не с Java-полями DTO. */
    static String toSnakeCase(String name) {
        if (name == null) {
            return null;
        }
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
