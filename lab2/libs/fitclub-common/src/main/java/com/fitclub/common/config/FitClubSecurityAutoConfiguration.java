package com.fitclub.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import com.fitclub.common.error.ProblemResponseWriter;
import com.fitclub.common.security.FitClubSecurityProperties;
import com.fitclub.common.security.InternalAuthProperties;
import com.fitclub.common.security.JwtDecoders;
import com.fitclub.common.security.ProblemAccessDeniedHandler;
import com.fitclub.common.security.ProblemAuthenticationEntryPoint;
import com.fitclub.common.security.RolesClaimAuthenticationConverter;
import com.fitclub.common.security.ServiceTokenFilter;

/**
 * Безопасность servlet-сервисов по умолчанию.
 * <ul>
 *   <li>Bearer JWT без сессий и cookie: STATELESS, CSRF отключён (токен не отправляется браузером
 *       автоматически), CORS не настраивается, потому что UI работает с того же origin через Nginx;</li>
 *   <li>/internal/** — только с X-Service-Token (ServiceTokenFilter), пользовательский JWT там не действует;</li>
 *   <li>технические пути и fitclub.security.public-paths открыты, остальное требует аутентификации;</li>
 *   <li>роли и владение ресурсами дополнительно проверяют контроллеры (@PreAuthorize) и use case'ы.</li>
 * </ul>
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({FitClubSecurityProperties.class, InternalAuthProperties.class})
@EnableMethodSecurity
public class FitClubSecurityAutoConfiguration {

    static final String[] TECHNICAL_PATHS = {
            "/health", "/ready", "/actuator/health", "/actuator/health/**",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**", "/error"};

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(FitClubSecurityProperties properties) {
        return JwtDecoders.rs256(properties.jwtPublicKey(), properties.issuer(), properties.audience());
    }

    @Bean
    ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint(ProblemResponseWriter writer) {
        return new ProblemAuthenticationEntryPoint(writer);
    }

    @Bean
    ProblemAccessDeniedHandler problemAccessDeniedHandler(ProblemResponseWriter writer) {
        return new ProblemAccessDeniedHandler(writer);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain fitclubSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            FitClubSecurityProperties securityProperties, InternalAuthProperties internalProperties,
            ProblemAuthenticationEntryPoint entryPoint, ProblemAccessDeniedHandler accessDeniedHandler,
            ProblemResponseWriter writer) throws Exception {
        // Фильтр создаётся здесь, а не @Bean: иначе Boot зарегистрировал бы его ещё и как servlet-фильтр
        ServiceTokenFilter serviceTokenFilter = new ServiceTokenFilter(internalProperties, writer);
        String[] publicPaths = securityProperties.publicPaths().toArray(String[]::new);

        http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(new RolesClaimAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(serviceTokenFilter, BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(TECHNICAL_PATHS).permitAll();
                    if (publicPaths.length > 0) {
                        requests.requestMatchers(publicPaths).permitAll();
                    }
                    requests.requestMatchers("/internal/**").hasRole("SERVICE");
                    requests.anyRequest().authenticated();
                });
        return http.build();
    }
}
