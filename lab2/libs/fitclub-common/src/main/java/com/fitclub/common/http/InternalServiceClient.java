package com.fitclub.common.http;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fitclub.common.error.ApiException;
import com.fitclub.common.security.ServiceTokenFilter;
import com.fitclub.common.web.RequestIds;

/**
 * HTTP-клиент для вызова /internal/** другого сервиса. Отвечает только за транспорт:
 * <ul>
 *   <li>раздельные connect/read timeout;</li>
 *   <li>X-Service-Token направления и X-Request-ID текущего запроса в каждом вызове;</li>
 *   <li>сбои транспорта → ApiException: нет соединения 503, таймаут ответа 504, 5xx/неверный ответ 502;</li>
 *   <li>404 → пустой Optional (решение принимает бизнес-код);</li>
 *   <li>повтор только для безопасных GET и только при временных сбоях (maxAttempts).</li>
 * </ul>
 * Вызывающий код не должен держать открытую транзакцию с блокировками во время вызова.
 */
public class InternalServiceClient {

    private static final Logger log = LoggerFactory.getLogger(InternalServiceClient.class);

    private final String targetName;
    private final RestClient restClient;
    private final int maxAttempts;
    private final Duration retryBackoff;

    public InternalServiceClient(String targetName, URI baseUrl, String serviceToken, Duration connectTimeout,
            Duration readTimeout, int maxAttempts, Duration retryBackoff) {
        this.targetName = targetName;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoff = retryBackoff;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl.toString())
                .requestFactory(factory)
                .defaultHeader(ServiceTokenFilter.HEADER, serviceToken)
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get(RequestIds.MDC_KEY);
                    if (requestId != null) {
                        request.getHeaders().set(RequestIds.HEADER, requestId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    /**
     * Безопасное чтение: GET по шаблону URI. 404 → Optional.empty().
     *
     * @param mapper преобразование тела в результат; IllegalArgumentException/NullPointerException из маппера
     *               означают некорректный ответ (502)
     */
    public <B, R> Optional<R> getOptional(String uriTemplate, Class<B> bodyType, Function<B, R> mapper,
            Object... uriVariables) {
        ApiException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                long started = System.nanoTime();
                B body = restClient.get().uri(uriTemplate, uriVariables).retrieve().body(bodyType);
                log.info("Internal call to {} succeeded ({} ms)", targetName, (System.nanoTime() - started) / 1_000_000);
                if (body == null) {
                    throw new IllegalArgumentException("empty body");
                }
                return Optional.of(mapper.apply(body));
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                    return Optional.empty();
                }
                last = translate(e);
                if (e.getStatusCode().is4xxClientError()) {
                    throw last;
                }
            } catch (RestClientException e) {
                last = translate(e);
            } catch (IllegalArgumentException | NullPointerException e) {
                log.warn("Internal call to {} returned malformed body", targetName);
                throw ApiException.dependencyBadResponse();
            }
            if (attempt < maxAttempts) {
                log.info("Retrying internal GET to {} after {} (attempt {}/{})", targetName, last.code(), attempt + 1,
                        maxAttempts);
                sleep(retryBackoff);
            }
        }
        throw last;
    }

    private ApiException translate(RestClientException e) {
        ApiException result;
        if (e instanceof RestClientResponseException response) {
            // 5xx — сбой сервиса; 401/403 — ошибка конфигурации токена. Клиенту в обоих случаях 502 без подробностей
            log.warn("Internal call to {} failed with HTTP {}", targetName, response.getStatusCode().value());
            return ApiException.dependencyBadResponse();
        }
        if (e instanceof ResourceAccessException) {
            Throwable cause = e.getMostSpecificCause();
            boolean connectProblem = cause instanceof ConnectException || cause instanceof UnknownHostException
                    || (cause instanceof SocketTimeoutException
                            && String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT).contains("connect"));
            result = connectProblem ? ApiException.dependencyUnavailable() : ApiException.dependencyTimeout();
        } else {
            result = ApiException.dependencyBadResponse();
        }
        MDC.put("error_code", result.code());
        try {
            log.warn("Internal call to {} failed: {}", targetName, e.getMostSpecificCause().getClass().getSimpleName());
        } finally {
            MDC.remove("error_code");
        }
        return result;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
