package com.fitclub.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.fitclub.common.error.ApiProblem;
import com.fitclub.common.error.ErrorCodes;
import com.fitclub.common.error.ProblemResponseWriter;

/**
 * Аутентификация межсервисных вызовов /internal/** по заголовку X-Service-Token.
 * <ul>
 *   <li>токен сравнивается за постоянное время (MessageDigest.isEqual);</li>
 *   <li>каждый токен принадлежит одному вызывающему и действует только на его пути;</li>
 *   <li>значение токена никогда не логируется.</li>
 * </ul>
 * Учебное решение для закрытой сети контейнеров; развитие — service JWT или mTLS (docs/security.md).
 */
public class ServiceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Service-Token";
    public static final String INTERNAL_PREFIX = "/internal/";

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenFilter.class);

    private final List<Caller> callers;
    private final ProblemResponseWriter writer;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public ServiceTokenFilter(InternalAuthProperties properties, ProblemResponseWriter writer) {
        this.callers = properties.clients().stream().map(Caller::of).toList();
        this.writer = writer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        Optional<Caller> caller = identify(request.getHeader(HEADER));
        if (caller.isEmpty()) {
            log.warn("Internal call rejected: missing or unknown service credential, path={}", path);
            writer.write(response, ApiProblem.of(401, ErrorCodes.INVALID_SERVICE_CREDENTIAL,
                    "Требуется служебная аутентификация", "Неверные служебные учётные данные.", path));
            return;
        }
        if (!caller.get().mayAccess(path)) {
            log.warn("Internal call rejected: caller={} is not allowed for path={}", caller.get().name(), path);
            writer.write(response, ApiProblem.of(403, ErrorCodes.ACCESS_DENIED, "Доступ запрещён",
                    "Этому сервису запрещён данный внутренний маршрут.", path));
            return;
        }
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(new PreAuthenticatedAuthenticationToken(caller.get().name(), null,
                AuthorityUtils.createAuthorityList("ROLE_SERVICE", "SERVICE_" + caller.get().name())));
        contextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private Optional<Caller> identify(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        Caller match = null;
        for (Caller caller : callers) {
            // Без досрочного выхода: время проверки не зависит от того, какой по счёту токен совпал
            if (MessageDigest.isEqual(caller.token(), presentedBytes)) {
                match = caller;
            }
        }
        return Optional.ofNullable(match);
    }

    private record Caller(String name, byte[] token, List<PathPattern> paths) {

        static Caller of(InternalAuthProperties.Client client) {
            List<PathPattern> patterns = client.paths().stream().map(PathPatternParser.defaultInstance::parse).toList();
            return new Caller(client.name(), client.token().getBytes(StandardCharsets.UTF_8), patterns);
        }

        boolean mayAccess(String path) {
            PathContainer container = PathContainer.parsePath(path);
            return paths.stream().anyMatch(pattern -> pattern.matches(container));
        }
    }
}
