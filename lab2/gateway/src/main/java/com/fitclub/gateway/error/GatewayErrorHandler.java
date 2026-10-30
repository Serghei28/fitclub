package com.fitclub.gateway.error;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import io.netty.channel.ConnectTimeoutException;
import reactor.core.publisher.Mono;

import com.fitclub.gateway.web.RequestIdFilter;

/**
 * Контролируемые ответы Gateway вместо стандартной страницы ошибок, всегда в формате problem+json:
 * <ul>
 *   <li>сервис не принимает соединение / имя не резолвится → 503 DEPENDENCY_UNAVAILABLE;</li>
 *   <li>сервис не ответил за response-timeout → 504 DEPENDENCY_TIMEOUT;</li>
 *   <li>соединение оборвано во время ответа → 502 DEPENDENCY_BAD_RESPONSE;</li>
 *   <li>нет маршрута → 404 RESOURCE_NOT_FOUND.</li>
 * </ul>
 * Ответы самих сервисов (в том числе их ошибки) проходят без изменений. Адреса сервисов в ответ не попадают.
 */
@Component
@Order(-2)
class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ProblemWriter problems;

    GatewayErrorHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        if (hasCause(error, ConnectException.class) || hasCause(error, ConnectTimeoutException.class)
                || hasCause(error, UnknownHostException.class) || error instanceof NotFoundException) {
            logDependency(exchange, "DEPENDENCY_UNAVAILABLE", error);
            return problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "Сервис временно недоступен", "Сервис временно недоступен. Повторите попытку позже.");
        }
        if (hasCause(error, TimeoutException.class) || statusOf(error) == HttpStatus.GATEWAY_TIMEOUT.value()) {
            logDependency(exchange, "DEPENDENCY_TIMEOUT", error);
            return problems.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "DEPENDENCY_TIMEOUT",
                    "Сервис не ответил вовремя", "Сервис не ответил вовремя. Повторите попытку позже.");
        }
        if (hasCause(error, ClosedChannelException.class) || hasCause(error, java.io.IOException.class)) {
            logDependency(exchange, "DEPENDENCY_BAD_RESPONSE", error);
            return problems.write(exchange, HttpStatus.BAD_GATEWAY, "DEPENDENCY_BAD_RESPONSE",
                    "Некорректный ответ сервиса", "Сервис вернул некорректный ответ. Повторите попытку позже.");
        }
        int status = statusOf(error);
        if (status == HttpStatus.NOT_FOUND.value()) {
            return problems.write(exchange, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Ресурс не найден",
                    "Запрошенный адрес не существует.");
        }
        if (status == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return problems.write(exchange, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                    "Метод не поддерживается", "Метод не поддерживается для этого адреса.");
        }
        if (status >= 400 && status < 500) {
            return problems.write(exchange, HttpStatus.valueOf(status), "MALFORMED_REQUEST", "Некорректный запрос",
                    "Запрос отклонён.");
        }
        withMdc(exchange, "INTERNAL_ERROR", () -> log.error("Unexpected gateway error", error));
        return problems.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка",
                "Произошла непредвиденная ошибка. Сообщите request_id администратору.");
    }

    private static void logDependency(ServerWebExchange exchange, String code, Throwable error) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        withMdc(exchange, code, () -> log.warn("Dependency failure on route {}: {}",
                route == null ? "none" : route.getId(), error.getClass().getSimpleName()));
    }

    private static void withMdc(ServerWebExchange exchange, String code, Runnable logging) {
        try {
            MDC.put("request_id", RequestIdFilter.requestId(exchange));
            MDC.put("error_code", code);
            logging.run();
        } finally {
            MDC.clear();
        }
    }

    private static int statusOf(Throwable error) {
        if (error instanceof ResponseStatusException rse) {
            HttpStatusCode code = rse.getStatusCode();
            return code.value();
        }
        return 500;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
