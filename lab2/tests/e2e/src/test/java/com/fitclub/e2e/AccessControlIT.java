package com.fitclub.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fitclub.e2e.support.Api.Response;
import com.fitclub.e2e.support.Fitclub;
import com.fitclub.e2e.support.Fitclub.Purchase;
import com.fitclub.e2e.support.Fitclub.User;

/** Аутентификация, роли и владение ресурсами — проверка через Gateway, как это видит внешний клиент. */
class AccessControlIT {

    private final Fitclub fc = new Fitclub();

    @Test
    @DisplayName("Без токена и с поддельным токеном — 401 problem+json; /internal снаружи закрыт")
    void authenticationAndInternalApi() {
        Response anonymous = fc.api.get("/api/bookings/me", null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body().has("code")).isTrue();
        assertThat(anonymous.requestId()).isNotEqualTo("-");

        Response forged = fc.api.get("/api/bookings/me", "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl");
        assertThat(forged.status()).isEqualTo(401);
        assertThat(forged.body().toString()).doesNotContain("Exception", "at com.");

        assertThat(fc.api.get("/internal/memberships/eligibility?user_id=" + UUID.randomUUID()
                + "&at=2030-01-01T00:00:00Z", null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("Клиент не назначает себе ADMIN и не управляет каталогом")
    void clientCannotEscalate() {
        User client = fc.client("plain");
        Response roles = fc.api.patch("/api/admin/users/" + client.id() + "/roles", client.token(),
                Map.of("roles", java.util.List.of("ADMIN")));
        assertThat(roles.status()).isEqualTo(403);
        Response plan = fc.api.post("/api/plans", client.token(),
                Map.of("name", "x", "duration_days", 30, "price_minor", 1, "currency", "MDL"));
        assertThat(plan.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("Чужие платёж, запись, уведомление и участники чужого занятия недоступны")
    void ownership() {
        User trainer = fc.trainer("owner-trainer");
        User otherTrainer = fc.trainer("other-trainer");
        UUID planId = fc.plan("E2E доступ", 5, 10000);
        UUID sessionId = fc.publishedSession(trainer, "E2E доступ", 50, 3);

        User owner = fc.client("owner");
        Purchase purchase = fc.pay(owner, planId);
        fc.awaitMembership(owner, purchase.orderId());
        Response booking = fc.book(owner, sessionId, Fitclub.key());
        assertThat(booking.status()).isEqualTo(201);

        User stranger = fc.client("stranger");
        assertThat(fc.api.get("/api/payments/" + purchase.paymentId(), stranger.token()).status()).isEqualTo(404);
        assertThat(fc.api.get("/api/membership-orders/" + purchase.orderId(), stranger.token()).status()).isEqualTo(404);
        assertThat(fc.api.post("/api/bookings/" + booking.id() + "/cancel", stranger.token(), null).status())
                .isEqualTo(404);

        Response notifications = fc.api.get("/api/notifications?size=100", owner.token());
        String notificationId = notifications.body().path("items").get(0).path("id").asString();
        assertThat(fc.api.patch("/api/notifications/" + notificationId + "/read", stranger.token(), null).status())
                .isEqualTo(404);

        // Участников видит назначенный тренер, но не другой тренер и не клиент
        assertThat(fc.api.get("/api/sessions/" + sessionId + "/bookings", trainer.token()).status()).isEqualTo(200);
        assertThat(fc.api.get("/api/sessions/" + sessionId + "/bookings", otherTrainer.token()).status()).isEqualTo(403);
        assertThat(fc.api.get("/api/sessions/" + sessionId + "/bookings", owner.token()).status()).isEqualTo(403);
    }
}
