package com.fitclub.common.config;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fitclub.common.error.GlobalExceptionHandler;
import com.fitclub.common.error.ProblemErrorController;
import com.fitclub.common.error.ProblemResponseWriter;
import com.fitclub.common.security.AuthenticatedUserArgumentResolver;
import com.fitclub.common.web.RequestIdFilter;

import tools.jackson.databind.json.JsonMapper;

/** HTTP-инфраструктура: request id, единый формат ошибок, AuthenticatedUser в контроллерах. */
@AutoConfiguration(beforeName = "org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FitClubWebAutoConfiguration {

    /** Самый первый фильтр: request_id есть даже в ответах Spring Security. */
    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    ProblemResponseWriter problemResponseWriter(JsonMapper jsonMapper) {
        return new ProblemResponseWriter(jsonMapper);
    }

    @Bean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /** Заменяет BasicErrorController Spring Boot: /error тоже отвечает problem+json. */
    @Bean
    ProblemErrorController problemErrorController() {
        return new ProblemErrorController();
    }

    @Bean
    WebMvcConfigurer authenticatedUserResolverConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new AuthenticatedUserArgumentResolver());
            }
        };
    }
}
