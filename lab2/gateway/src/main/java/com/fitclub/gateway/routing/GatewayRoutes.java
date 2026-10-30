package com.fitclub.gateway.routing;

import java.net.URI;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fitclub.gateway.config.GatewayProperties;

/**
 * Публичные маршруты /api → сервис-владелец. Порядок задан явно: чем меньше order, тем раньше проверяется.
 * <ul>
 *   <li>/api/sessions/{id}/bookings (участники занятия) принадлежит booking-service и проверяется РАНЬШЕ общего
 *       /api/sessions/** schedule-service — иначе запрос ушёл бы не тому сервису;</li>
 *   <li>/internal/** здесь нет вообще: межсервисные маршруты наружу не публикуются (см. PathGuardFilter);</li>
 *   <li>/docs/{service}/v3/api-docs — OpenAPI каждого сервиса для общего Swagger UI Gateway.</li>
 * </ul>
 * Пути и методы не переписываются: сервис получает тот же /api/... путь.
 */
@Configuration(proxyBeanMethods = false)
class GatewayRoutes {

    @Bean
    RouteLocator fitclubRoutes(RouteLocatorBuilder builder, GatewayProperties properties) {
        GatewayProperties.Services svc = properties.services();
        return builder.routes()
                .route("booking-session-participants", r -> r.order(0)
                        .path("/api/sessions/{id}/bookings").uri(svc.booking()))
                .route("auth", r -> r.order(10)
                        .path("/api/auth/**", "/api/admin/users", "/api/admin/users/**").uri(svc.auth()))
                .route("schedule", r -> r.order(20)
                        .path("/api/training-types", "/api/training-types/**", "/api/sessions", "/api/sessions/**")
                        .uri(svc.schedule()))
                .route("membership", r -> r.order(20)
                        .path("/api/plans", "/api/plans/**", "/api/membership-orders", "/api/membership-orders/**",
                                "/api/memberships/**")
                        .uri(svc.membership()))
                .route("payment", r -> r.order(20)
                        .path("/api/payments", "/api/payments/**").uri(svc.payment()))
                .route("booking", r -> r.order(20)
                        .path("/api/bookings", "/api/bookings/**", "/api/availability").uri(svc.booking()))
                .route("notification", r -> r.order(20)
                        .path("/api/notifications", "/api/notifications/**").uri(svc.notification()))
                .route("docs-auth", r -> docs(r, "auth", svc.auth()))
                .route("docs-schedule", r -> docs(r, "schedule", svc.schedule()))
                .route("docs-membership", r -> docs(r, "membership", svc.membership()))
                .route("docs-booking", r -> docs(r, "booking", svc.booking()))
                .route("docs-payment", r -> docs(r, "payment", svc.payment()))
                .route("docs-notification", r -> docs(r, "notification", svc.notification()))
                .build();
    }

    private static org.springframework.cloud.gateway.route.builder.Buildable<org.springframework.cloud.gateway.route.Route> docs(
            org.springframework.cloud.gateway.route.builder.PredicateSpec r, String service, URI uri) {
        return r.order(30).path("/docs/" + service + "/v3/api-docs")
                .filters(f -> f.setPath("/v3/api-docs"))
                .uri(uri);
    }
}
