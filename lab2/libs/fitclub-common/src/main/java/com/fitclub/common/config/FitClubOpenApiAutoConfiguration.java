package com.fitclub.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Общая часть OpenAPI: схема bearer JWT для публичного API и X-Service-Token для /internal/**.
 * Название и описание задаются в конфигурации сервиса (fitclub.openapi.*).
 */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
public class FitClubOpenApiAutoConfiguration {

    public static final String BEARER_SCHEME = "bearerAuth";
    public static final String SERVICE_TOKEN_SCHEME = "serviceToken";

    @Bean
    @ConditionalOnMissingBean
    OpenAPI fitclubOpenApi(@Value("${fitclub.openapi.title:${spring.application.name}}") String title,
            @Value("${fitclub.openapi.description:}") String description,
            @Value("${fitclub.openapi.version:1.0.0}") String version) {
        return new OpenAPI()
                .info(new Info().title(title).description(description).version(version))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("Access JWT из POST /api/auth/login"))
                        .addSecuritySchemes(SERVICE_TOKEN_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                                .name("X-Service-Token")
                                .description("Только для внутренних маршрутов /internal/**, не для браузера")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
