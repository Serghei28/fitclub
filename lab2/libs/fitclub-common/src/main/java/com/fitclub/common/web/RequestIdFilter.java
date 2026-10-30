package com.fitclub.common.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Первый фильтр каждого HTTP-запроса: назначает request_id, кладёт его в MDC и в ответ,
 * пишет одну строку итогового лога запроса и гарантированно очищает MDC.
 * Тело запроса, query string и заголовки (включая Authorization) не логируются.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = RequestIds.acceptOrGenerate(request.getHeader(RequestIds.HEADER));
        long started = System.nanoTime();
        MDC.put(RequestIds.MDC_KEY, requestId);
        response.setHeader(RequestIds.HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (!isTechnical(request.getRequestURI())) {
                long durationMs = (System.nanoTime() - started) / 1_000_000;
                MDC.put("duration_ms", Long.toString(durationMs));
                MDC.put("http_status", Integer.toString(response.getStatus()));
                log.info("HTTP {} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), durationMs);
            }
            MDC.clear();
        }
    }

    /** Частые проверки health и статика Swagger не засоряют лог. */
    private static boolean isTechnical(String uri) {
        return uri.equals("/health") || uri.equals("/ready") || uri.startsWith("/actuator")
                || uri.startsWith("/swagger-ui") || uri.startsWith("/v3/api-docs");
    }
}
