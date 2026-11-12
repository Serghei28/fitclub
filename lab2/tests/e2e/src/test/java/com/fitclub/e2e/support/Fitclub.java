package com.fitclub.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import tools.jackson.databind.JsonNode;

import com.fitclub.e2e.support.Api.Response;

/**
 * Шаги пользовательских сценариев через публичный API. Каждый запуск создаёт свои данные с уникальным
 * суффиксом (email, названия тарифов и типов), поэтому тесты не зависят друг от друга и от demo-данных.
 */
public final class Fitclub {

    public static final Duration ASYNC = Duration.ofSeconds(45);

    public final Api api;
    public final Stack stack;
    public final String run = Long.toString(System.currentTimeMillis(), 36)
            + Integer.toString(ThreadLocalRandom.current().nextInt(1296), 36);
    private final String adminEmail;
    private final Path projectDir;
    private String adminToken;

    public record User(UUID id, String email, String token) {
    }

    public record Purchase(UUID orderId, UUID paymentId, String paymentKey, String simulateKey) {
    }

    public Fitclub() {
        String baseUrl = System.getProperty("e2e.baseUrl", "http://localhost:18000");
        this.projectDir = Path.of(System.getProperty("e2e.projectDir", "../..")).toAbsolutePath().normalize();
        this.adminEmail = System.getProperty("e2e.adminEmail", "admin@fitclub.local");
        this.api = new Api(baseUrl);
        this.stack = new Stack(System.getProperty("e2e.composeProject", "fitclub-e2e"), projectDir);
    }

    public static String key() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------ пользователи

    /** Вход demo-администратора; пароль читается из secrets/ и нигде не выводится. */
    public String admin() {
        if (adminToken == null) {
            String password;
            try {
                password = Files.readString(projectDir.resolve("secrets/auth_initial_admin_password"),
                        StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new IllegalStateException("Нет secrets/auth_initial_admin_password: запустите генератор секретов", e);
            }
            adminToken = login(adminEmail, password);
        }
        return adminToken;
    }

    public String login(String email, String password) {
        Response response = api.post("/api/auth/login", null, Map.of("email", email, "password", password));
        assertThat(response.status()).as("вход %s: %s", email, response).isEqualTo(200);
        return response.text("access_token");
    }

    /** Новый клиент: регистрация и вход. Пароль генерируется на один запуск теста. */
    public User client(String name) {
        return register(name, null);
    }

    /** Тренер: роль назначает администратор; новые роли попадают в JWT при следующем входе. */
    public User trainer(String name) {
        return register(name, List.of("CLIENT", "TRAINER"));
    }

    private User register(String name, List<String> roles) {
        String email = name + "." + run + "@e2e.test";
        String password = "E2e-" + UUID.randomUUID();
        Response registered = api.post("/api/auth/register", null,
                Map.of("email", email, "password", password, "display_name", "E2E " + name));
        assertThat(registered.status()).as("регистрация: %s", registered).isEqualTo(201);
        if (roles != null) {
            Response changed = api.patch("/api/admin/users/" + registered.id() + "/roles", admin(),
                    Map.of("roles", roles));
            assertThat(changed.status()).as("назначение ролей: %s", changed).isEqualTo(200);
        }
        return new User(registered.id(), email, login(email, password));
    }

    // ------------------------------------------------------------------ каталог и расписание

    public UUID plan(String name, Integer visitLimit, long priceMinor) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name + " " + run);
        body.put("duration_days", 30);
        body.put("visit_limit", visitLimit);
        body.put("price_minor", priceMinor);
        body.put("currency", "MDL");
        Response created = api.post("/api/plans", admin(), body);
        assertThat(created.status()).as("тариф: %s", created).isEqualTo(201);
        return created.id();
    }

    public UUID trainingType() {
        Response created = api.post("/api/training-types", admin(),
                Map.of("name", "E2E " + run + " " + ThreadLocalRandom.current().nextInt(100000),
                        "description", "Тип для сквозных проверок"));
        assertThat(created.status()).as("тип тренировки: %s", created).isEqualTo(201);
        return created.id();
    }

    /** Опубликованное занятие через {@code hoursAhead} часов; ждёт, пока Booking получит его по событию. */
    public UUID publishedSession(User trainer, String title, int hoursAhead, int capacity) {
        Instant start = Instant.now().plus(hoursAhead, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        Response created = api.post("/api/sessions", admin(), Map.of(
                "training_type_id", trainingType(),
                "trainer_id", trainer.id(),
                "title", title + " " + run,
                "starts_at", start.toString(),
                "ends_at", start.plus(1, ChronoUnit.HOURS).toString(),
                "capacity", capacity));
        assertThat(created.status()).as("занятие: %s", created).isEqualTo(201);
        UUID id = created.id();
        Response published = api.post("/api/sessions/" + id + "/publish", admin(), null);
        assertThat(published.status()).as("публикация: %s", published).isEqualTo(200);
        await("Booking получил SessionPublished").atMost(ASYNC).pollInterval(Duration.ofMillis(500))
                .until(() -> "OPEN".equals(availability(id).path("state").asString("")));
        return id;
    }

    public JsonNode availability(UUID sessionId) {
        Response response = api.get("/api/availability?session_id=" + sessionId, admin());
        return response.status() == 200 && response.body().size() > 0 ? response.body().get(0)
                : tools.jackson.databind.node.MissingNode.getInstance();
    }

    // ------------------------------------------------------------------ абонемент

    /** Заказ → платёж → тестовая оплата; без ожидания активации. */
    public Purchase pay(User client, UUID planId) {
        Response order = api.post("/api/membership-orders", client.token(), Map.of("plan_id", planId), key());
        assertThat(order.status()).as("заказ: %s", order).isEqualTo(201);
        String paymentKey = key();
        Response payment = api.post("/api/payments", client.token(),
                Map.of("membership_order_id", order.id()), paymentKey);
        assertThat(payment.status()).as("платёж: %s", payment).isEqualTo(201);
        String simulateKey = key();
        Response simulated = api.post("/api/payments/" + payment.id() + "/simulate", client.token(),
                Map.of("outcome", "SUCCESS"), simulateKey);
        assertThat(simulated.status()).as("тестовая оплата (нужен профиль demo): %s", simulated).isEqualTo(200);
        assertThat(simulated.text("status")).isEqualTo("SUCCEEDED");
        return new Purchase(order.id(), payment.id(), paymentKey, simulateKey);
    }

    public JsonNode awaitMembership(User client, UUID orderId) {
        return await("абонемент по заказу " + orderId).atMost(ASYNC).pollInterval(Duration.ofMillis(500))
                .until(() -> membershipFor(client, orderId), node -> !node.isMissingNode());
    }

    /** То же с запасом времени: после восстановления брокера relay повторяет отправку с нарастающей паузой. */
    public JsonNode awaitMembershipLong(User client, UUID orderId) {
        return await("абонемент по заказу " + orderId).atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(1))
                .until(() -> membershipFor(client, orderId), node -> !node.isMissingNode());
    }

    public JsonNode membershipFor(User client, UUID orderId) {
        Response response = api.get("/api/memberships/me", client.token());
        if (response.status() == 200) {
            for (JsonNode m : response.body()) {
                if (orderId.toString().equals(m.path("order_id").asString())) {
                    return m;
                }
            }
        }
        return tools.jackson.databind.node.MissingNode.getInstance();
    }

    public User clientWithMembership(String name, UUID planId) {
        User client = client(name);
        Purchase purchase = pay(client, planId);
        assertThat(awaitMembership(client, purchase.orderId()).path("status").asString()).isEqualTo("ACTIVE");
        return client;
    }

    public Response book(User client, UUID sessionId, String idempotencyKey) {
        return api.post("/api/bookings", client.token(), Map.of("session_id", sessionId), idempotencyKey);
    }

    public JsonNode usage(User client) {
        Response response = api.get("/api/bookings/usage/me", client.token());
        assertThat(response.status()).as("учёт посещений: %s", response).isEqualTo(200);
        return response.body().get(0);
    }

    /** Есть ли у пользователя уведомление данного типа (по заголовку и тексту, через Notification API). */
    public boolean hasNotification(User user, String type, String textFragment) {
        Response response = api.get("/api/notifications?size=100", user.token());
        if (response.status() != 200) {
            return false;
        }
        for (JsonNode n : response.body().path("items")) {
            if (type.equals(n.path("type").asString()) && n.path("body").asString("").contains(textFragment)) {
                return true;
            }
        }
        return false;
    }

    public long countNotifications(User user, String type) {
        Response response = api.get("/api/notifications?size=100", user.token());
        long count = 0;
        for (JsonNode n : response.body().path("items")) {
            if (type.equals(n.path("type").asString())) {
                count++;
            }
        }
        return count;
    }
}
