package com.fitclub.gateway.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

import com.fitclub.gateway.config.GatewayProperties;
import com.fitclub.gateway.error.ProblemWriter;

/**
 * Проверка пользовательских JWT на защищённых маршрутах /api/**: подпись RS256 публичным ключом,
 * iss, aud, exp, sub — те же правила, что в сервисах. Недействительный токен отсекается здесь,
 * не нагружая сервисы, но роли и владение ресурсами проверяет только сам сервис.
 * Без сессий и cookie: CSRF не нужен, CORS не настраивается (UI работает с того же origin через Nginx).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class GatewaySecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewaySecurityConfiguration.class);

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(GatewayProperties properties) {
        GatewayProperties.Jwt jwt = properties.jwt();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(readPublicKey(jwt))
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
                new JwtIssuerValidator(jwt.issuer()),
                new JwtAudienceValidator(jwt.audience()),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank())));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, ReactiveJwtDecoder decoder,
            ProblemWriter problems) {
        ServerAuthenticationEntryPoint entryPoint = (exchange, ex) -> {
            boolean invalidToken = ex instanceof OAuth2AuthenticationException;
            log.info("Gateway rejected request: {}", invalidToken ? "INVALID_TOKEN" : "AUTHENTICATION_REQUIRED");
            exchange.getResponse().getHeaders().set("WWW-Authenticate",
                    invalidToken ? "Bearer error=\"invalid_token\"" : "Bearer");
            return invalidToken
                    ? problems.write(exchange, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Недействительный токен",
                            "Токен доступа недействителен или истёк. Войдите снова.")
                    : problems.write(exchange, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Требуется вход",
                            "Для этого действия нужно войти в систему.");
        };
        ServerAccessDeniedHandler deniedHandler = (exchange, ex) -> problems.write(exchange, HttpStatus.FORBIDDEN,
                "ACCESS_DENIED", "Доступ запрещён", "Недостаточно прав для этого действия.");

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .oauth2ResourceServer(rs -> rs.jwt(j -> j.jwtDecoder(decoder))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .authorizeExchange(ex -> ex
                        .pathMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                                "/api/auth/logout").permitAll()
                        .pathMatchers("/api/**").authenticated()
                        // health, Swagger UI и OpenAPI сервисов; неизвестные пути получат 404
                        .anyExchange().permitAll())
                .build();
    }

    private static RSAPublicKey readPublicKey(GatewayProperties.Jwt jwt) {
        try (InputStream in = jwt.publicKey().getInputStream()) {
            return RsaKeyConverters.x509().convert(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read JWT public key from " + jwt.publicKey().getDescription(), e);
        }
    }
}
