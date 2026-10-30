package com.fitclub.gateway.error;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import com.fitclub.gateway.web.RequestIdFilter;

/** Запись GatewayProblem в реактивный ответ: из фильтров, обработчиков Security и ErrorWebExceptionHandler. */
@Component
public class ProblemWriter {

    private final JsonMapper jsonMapper;

    public ProblemWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String title, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        GatewayProblem problem = GatewayProblem.of(status.value(), code, title, detail,
                exchange.getRequest().getPath().value(), RequestIdFilter.requestId(exchange));
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(jsonMapper.writeValueAsBytes(problem));
        return response.writeWith(Mono.just(buffer));
    }
}
