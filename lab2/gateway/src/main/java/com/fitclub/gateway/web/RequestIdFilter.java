package com.fitclub.gateway.web;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Первый фильтр Gateway: принимает X-Request-ID только в допустимом формате (иначе создаёт новый),
 * передаёт его сервису в запросе, возвращает клиенту и пишет одну строку лога на запрос.
 * В реактивном коде нет «потока запроса», поэтому MDC заполняется только на время записи строки лога.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

    public static final String HEADER = "X-Request-ID";
    static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = incoming != null && VALID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, requestId))
                .build();
        ServerWebExchange mutated = exchange.mutate().request(request).build();
        mutated.getAttributes().put(ATTRIBUTE, requestId);
        mutated.getResponse().getHeaders().set(HEADER, requestId);

        long started = System.nanoTime();
        return chain.filter(mutated).doFinally(signal -> logRequest(mutated, requestId, started));
    }

    public static String requestId(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(ATTRIBUTE);
        return value == null ? null : value.toString();
    }

    private static void logRequest(ServerWebExchange exchange, String requestId, long started) {
        String path = exchange.getRequest().getPath().value();
        if (path.equals("/health") || path.equals("/ready") || path.startsWith("/actuator")) {
            return;
        }
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        Integer status = exchange.getResponse().getStatusCode() == null
                ? null : exchange.getResponse().getStatusCode().value();
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        try {
            MDC.put("request_id", requestId);
            MDC.put("duration_ms", Long.toString(durationMs));
            MDC.put("http_status", String.valueOf(status));
            MDC.put("route", route == null ? "none" : route.getId());
            log.info("HTTP {} {} -> {} ({} ms)", exchange.getRequest().getMethod(), path, status, durationMs);
        } finally {
            MDC.clear();
        }
    }
}
