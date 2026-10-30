package com.fitclub.common.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.fitclub.common.error.ApiProblem;
import com.fitclub.common.error.ErrorCodes;
import com.fitclub.common.error.ProblemResponseWriter;

/**
 * 401 в формате problem+json: нет токена (AUTHENTICATION_REQUIRED) или токен недействителен
 * (INVALID_TOKEN: подпись, срок, iss/aud, алгоритм). Причина отказа пишется в лог, а не в ответ.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ProblemAuthenticationEntryPoint.class);

    private final ProblemResponseWriter writer;

    public ProblemAuthenticationEntryPoint(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        boolean invalidToken = authException instanceof OAuth2AuthenticationException;
        ApiProblem problem = invalidToken
                ? ApiProblem.of(401, ErrorCodes.INVALID_TOKEN, "Недействительный токен",
                        "Токен доступа недействителен или истёк. Войдите снова.", request.getRequestURI())
                : ApiProblem.of(401, ErrorCodes.AUTHENTICATION_REQUIRED, "Требуется вход",
                        "Для этого действия нужно войти в систему.", request.getRequestURI());
        log.info("Authentication failed: {} ({})", problem.code(), authException.getClass().getSimpleName());
        response.setHeader("WWW-Authenticate", invalidToken ? "Bearer error=\"invalid_token\"" : "Bearer");
        writer.write(response, problem);
    }
}
