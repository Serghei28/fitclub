package com.fitclub.common.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fitclub.common.web.RequestIds;

/**
 * Замена стандартной страницы ошибок Spring Boot (/error). Сюда попадают ошибки, которые возникли
 * вне контроллеров: отказ файрвола Spring Security (например, «//» в пути), sendError контейнера.
 * Ответ — тот же problem+json, что и у GlobalExceptionHandler, без внутренних подробностей.
 */
@RestController
public class ProblemErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ApiProblem> error(HttpServletRequest request) {
        HttpStatus status = resolveStatus(request);
        String instance = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        // MDC исходного запроса уже очищен: request_id восстанавливается из атрибута запроса
        Object requestId = request.getAttribute(RequestIds.ATTRIBUTE);
        if (requestId != null) {
            MDC.put(RequestIds.MDC_KEY, requestId.toString());
        }
        try {
            ApiProblem problem = switch (status) {
                case BAD_REQUEST -> ApiProblem.of(400, ErrorCodes.MALFORMED_REQUEST, "Некорректный запрос",
                        "Запрос отклонён: проверьте адрес и параметры.", instance);
                case UNAUTHORIZED -> ApiProblem.of(401, ErrorCodes.AUTHENTICATION_REQUIRED, "Требуется вход",
                        "Для этого действия нужно войти в систему.", instance);
                case FORBIDDEN -> ApiProblem.of(403, ErrorCodes.ACCESS_DENIED, "Доступ запрещён",
                        "Недостаточно прав для этого действия.", instance);
                case NOT_FOUND -> ApiProblem.of(404, ErrorCodes.RESOURCE_NOT_FOUND, "Ресурс не найден",
                        "Запрошенный адрес не существует.", instance);
                case METHOD_NOT_ALLOWED -> ApiProblem.of(405, ErrorCodes.METHOD_NOT_ALLOWED,
                        "Метод не поддерживается", "Метод не поддерживается для этого адреса.", instance);
                default -> ApiProblem.of(status.value(), ErrorCodes.INTERNAL_ERROR, "Внутренняя ошибка",
                        "Произошла непредвиденная ошибка. Сообщите request_id администратору.", instance);
            };
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
        } finally {
            MDC.remove(RequestIds.MDC_KEY);
        }
    }

    private static HttpStatus resolveStatus(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer value) {
            HttpStatus status = HttpStatus.resolve(value);
            if (status != null && status.isError()) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
