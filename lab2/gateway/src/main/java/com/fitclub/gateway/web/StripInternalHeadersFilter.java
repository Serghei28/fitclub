package com.fitclub.gateway.web;

import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Удаляет из пользовательских запросов служебные заголовки до передачи сервису.
 * Пользователь не может прислать X-Service-Token через Gateway; внутренние endpoint'ы при этом
 * всё равно проверяют токен сами (Gateway — не единственная линия защиты).
 */
@Component
public class StripInternalHeadersFilter implements GlobalFilter, Ordered {

    static final List<String> STRIPPED = List.of("X-Service-Token");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> STRIPPED.forEach(headers::remove))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
