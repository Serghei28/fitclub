package com.fitclub.common.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.fitclub.common.error.ApiProblem;
import com.fitclub.common.error.ErrorCodes;
import com.fitclub.common.error.ProblemResponseWriter;

/** 403 в формате problem+json для отказов на уровне фильтров Spring Security. */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemAccessDeniedHandler.class);

    private final ProblemResponseWriter writer;

    public ProblemAccessDeniedHandler(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        log.info("Access denied for {} {}", request.getMethod(), request.getRequestURI());
        writer.write(response, ApiProblem.of(403, ErrorCodes.ACCESS_DENIED, "Доступ запрещён",
                "Недостаточно прав для этого действия.", request.getRequestURI()));
    }
}
