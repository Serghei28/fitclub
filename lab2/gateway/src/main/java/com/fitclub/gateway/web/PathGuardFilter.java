package com.fitclub.gateway.web;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

import com.fitclub.gateway.error.ProblemWriter;

/**
 * Защита путей до маршрутизации и до проверки токена:
 * <ul>
 *   <li>/internal/** снаружи не существует — 404 без обращения к сервисам;</li>
 *   <li>пути с «..», «//», «;», обратной косой и их URL-кодированными формами отклоняются (400):
 *       иначе «/api/../internal/x» совпал бы с маршрутом /api/**, а сервис нормализовал бы его в /internal/x.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class PathGuardFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(PathGuardFilter.class);
    private static final List<String> FORBIDDEN_FRAGMENTS =
            List.of("..", "//", ";", "\\", "%2e", "%2f", "%5c", "%3b", "%00");

    private final ProblemWriter problems;

    public PathGuardFilter(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawPath = exchange.getRequest().getURI().getRawPath();
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_FRAGMENTS.stream().anyMatch(lower::contains)) {
            log.warn("Rejected suspicious path");
            return problems.write(exchange, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Некорректный запрос",
                    "Запрос отклонён: недопустимый путь.");
        }
        if (lower.equals("/internal") || lower.startsWith("/internal/")) {
            log.warn("Rejected external call to internal route");
            return problems.write(exchange, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Ресурс не найден",
                    "Запрошенный адрес не существует.");
        }
        return chain.filter(exchange);
    }
}
