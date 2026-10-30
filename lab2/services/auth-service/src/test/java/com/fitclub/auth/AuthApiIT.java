package com.fitclub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fitclub.auth.application.EnsureInitialAdminUseCase;
import com.fitclub.auth.application.RegisterUserUseCase;
import com.fitclub.auth.domain.AuthErrors;
import com.fitclub.common.error.DomainException;
import com.fitclub.testsupport.PostgresTestcontainers;
import com.fitclub.testsupport.TestJwt;

/** Сценарии auth-service через HTTP на настоящем PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestcontainers.class)
class AuthApiIT {

    static final String SCHEDULE_TOKEN = "test-service-token-schedule-to-auth-0123456789abcdef";
    static final String PASSWORD = "S3cure-pass";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RegisterUserUseCase registerUser;

    @Autowired
    EnsureInitialAdminUseCase ensureInitialAdmin;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        AuthTestKeys.register(registry);
    }

    static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    ResultActions register(String email, String password, String extraJson) throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s","display_name":"Анна Петрова"%s}
                """.formatted(email, password, extraJson)));
    }

    ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    String loginBody(String email, String password) throws Exception {
        return login(email, password).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"%s\"}".formatted(refreshToken)));
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    static List<String> rolesInToken(String accessToken) {
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
                StandardCharsets.UTF_8);
        return JsonPath.read(payload, "$.roles");
    }

    @Nested
    class Registration {

        @Test
        void publicRegistrationAssignsOnlyClientAndIgnoresRequestedRoles() throws Exception {
            register(uniqueEmail(), PASSWORD, ",\"roles\":[\"ADMIN\",\"TRAINER\"]")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.roles").value(containsInAnyOrder("CLIENT")))
                    .andExpect(content().string(not(containsString("password"))));
        }

        @Test
        void emailIsNormalizedAndUniqueRegardlessOfCase() throws Exception {
            String email = uniqueEmail();
            register("  " + email.toUpperCase() + " ", PASSWORD, "")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email").value(email));
            register(email, PASSWORD, "")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(AuthErrors.EMAIL_ALREADY_USED));
        }

        @Test
        void concurrentRegistrationWithSameEmailCreatesExactlyOneUser() throws Exception {
            String email = uniqueEmail();
            int threads = 6;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Boolean> attempt = () -> {
                    start.await();
                    try {
                        registerUser.register(new RegisterUserUseCase.Command(email, PASSWORD, "Гонка"));
                        return true;
                    } catch (DomainException e) {
                        assertThat(e.code()).isEqualTo(AuthErrors.EMAIL_ALREADY_USED);
                        return false;
                    }
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();
            int created = 0;
            for (Future<Boolean> result : results) {
                created += result.get() ? 1 : 0;
            }
            pool.shutdown();

            assertThat(created).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email))
                    .isEqualTo(1);
        }

        @Test
        void passwordRulesAreValidated() throws Exception {
            register(uniqueEmail(), "short", "")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(AuthErrors.WEAK_PASSWORD))
                    .andExpect(jsonPath("$.field_errors[0].field").value("password"));
            register(uniqueEmail(), "я".repeat(37), "")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(AuthErrors.PASSWORD_TOO_LONG));
        }

        @Test
        void passwordIsStoredAsBcryptHash() throws Exception {
            String email = uniqueEmail();
            register(email, PASSWORD, "").andExpect(status().isCreated());
            String hash = jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);
            assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
        }
    }

    @Nested
    class LoginAndTokens {

        @Test
        void wrongPasswordAndUnknownEmailReturnSameProblem() throws Exception {
            String email = uniqueEmail();
            register(email, PASSWORD, "").andExpect(status().isCreated());
            login(email, "wrong-password")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(AuthErrors.INVALID_CREDENTIALS));
            login(uniqueEmail(), PASSWORD)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(AuthErrors.INVALID_CREDENTIALS));
        }

        @Test
        void loginReturnsTokensThatOpenProfile() throws Exception {
            String email = uniqueEmail();
            register(email, PASSWORD, "").andExpect(status().isCreated());
            String body = loginBody(email.toUpperCase(), PASSWORD);
            String access = JsonPath.read(body, "$.access_token");

            assertThat((Integer) JsonPath.read(body, "$.expires_in")).isBetween(890, 900);
            assertThat(rolesInToken(access)).containsExactly("CLIENT");
            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(access)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(email));
        }

        @Test
        void forgedTokenIsRejected() throws Exception {
            String forged = TestJwt.builder(UUID.randomUUID(), "ADMIN").signedByUntrustedKey().sign();
            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(forged)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        }

        @Test
        void refreshRotatesAndReuseRevokesFamily() throws Exception {
            String email = uniqueEmail();
            register(email, PASSWORD, "").andExpect(status().isCreated());
            String first = JsonPath.read(loginBody(email, PASSWORD), "$.refresh_token");

            String rotated = JsonPath.read(refresh(first).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString(), "$.refresh_token");
            assertThat(rotated).isNotEqualTo(first);

            refresh(first).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(AuthErrors.INVALID_REFRESH_TOKEN));
            // Повторное использование старого токена отозвало и новый
            refresh(rotated).andExpect(status().isUnauthorized());
        }

        @Test
        void logoutRevokesRefreshButIssuedAccessTokenLivesUntilExpiry() throws Exception {
            String email = uniqueEmail();
            register(email, PASSWORD, "").andExpect(status().isCreated());
            String body = loginBody(email, PASSWORD);
            String access = JsonPath.read(body, "$.access_token");
            String refreshToken = JsonPath.read(body, "$.refresh_token");

            mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refresh_token\":\"%s\"}".formatted(refreshToken)))
                    .andExpect(status().isNoContent());

            refresh(refreshToken).andExpect(status().isUnauthorized());
            // Задокументированное ограничение: access JWT проверяется локально и действует до exp (15 минут)
            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(access)))
                    .andExpect(status().isOk());
        }

        @Test
        void refreshTokenIsStoredOnlyAsHash() throws Exception {
            String email = uniqueEmail();
            register(email, PASSWORD, "").andExpect(status().isCreated());
            String refreshToken = JsonPath.read(loginBody(email, PASSWORD), "$.refresh_token");
            Integer plainMatches = jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?",
                    Integer.class, refreshToken);
            assertThat(plainMatches).isZero();
        }
    }

    @Nested
    class Administration {

        String adminAccessToken() throws Exception {
            ensureInitialAdmin.ensure("admin@fitclub.test", "Admin-pass-1", "Администратор");
            return JsonPath.read(loginBody("admin@fitclub.test", "Admin-pass-1"), "$.access_token");
        }

        @Test
        void clientCannotUseAdminApi() throws Exception {
            String email = uniqueEmail();
            register(email, PASSWORD, "").andExpect(status().isCreated());
            String access = JsonPath.read(loginBody(email, PASSWORD), "$.access_token");
            mvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(access)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        void adminListsUsersWithRoleFilterAndPageLimit() throws Exception {
            String admin = adminAccessToken();
            String email = uniqueEmail();
            register(email, PASSWORD, "").andExpect(status().isCreated());

            mvc.perform(get("/api/admin/users").param("role", "CLIENT").param("q", email)
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total_elements").value(1))
                    .andExpect(jsonPath("$.items[0].email").value(email));
            mvc.perform(get("/api/admin/users").param("size", "101").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        void adminAssignsTrainerRoleWhichAppearsInNextToken() throws Exception {
            String admin = adminAccessToken();
            String email = uniqueEmail();
            String userId = JsonPath.read(register(email, PASSWORD, "").andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8), "$.id");

            mvc.perform(patch("/api/admin/users/{id}/roles", userId).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"CLIENT\",\"TRAINER\"]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles").value(containsInAnyOrder("CLIENT", "TRAINER")));

            String access = JsonPath.read(loginBody(email, PASSWORD), "$.access_token");
            assertThat(rolesInToken(access)).containsExactly("CLIENT", "TRAINER");
        }

        @Test
        void adminCannotRemoveOwnAdminRole() throws Exception {
            String admin = adminAccessToken();
            String adminId = JsonPath.read(mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION,
                    bearer(admin))).andReturn().getResponse().getContentAsString(), "$.id");
            mvc.perform(patch("/api/admin/users/{id}/roles", adminId).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"CLIENT\"]}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(AuthErrors.CANNOT_REVOKE_OWN_ADMIN));
        }

        @Test
        void unknownRoleIsBadRequest() throws Exception {
            String admin = adminAccessToken();
            mvc.perform(patch("/api/admin/users/{id}/roles", UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"SUPERUSER\"]}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void initialAdminBootstrapIsIdempotent() throws Exception {
            adminAccessToken();
            assertThat(ensureInitialAdmin.ensure("admin@fitclub.test", "Admin-pass-1", "Администратор"))
                    .isEqualTo(EnsureInitialAdminUseCase.Result.ALREADY_PRESENT);
            assertThat(jdbc.queryForObject("select count(*) from user_roles where role_code = 'ADMIN'",
                    Integer.class)).isEqualTo(1);
        }
    }

    @Nested
    class InternalTrainerStatus {

        @Test
        void requiresScheduleServiceToken() throws Exception {
            mvc.perform(get("/internal/users/{id}/trainer-status", UUID.randomUUID()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_SERVICE_CREDENTIAL"));
        }

        @Test
        void reportsTrainerRoleAndDisplayName() throws Exception {
            String email = uniqueEmail();
            String userId = JsonPath.read(register(email, PASSWORD, "").andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8), "$.id");

            mvc.perform(get("/internal/users/{id}/trainer-status", userId).header("X-Service-Token", SCHEDULE_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trainer").value(false))
                    .andExpect(jsonPath("$.display_name").value("Анна Петрова"));

            jdbc.update("insert into user_roles (user_id, role_code, granted_at) values (?::uuid, 'TRAINER', now())",
                    userId);
            mvc.perform(get("/internal/users/{id}/trainer-status", userId).header("X-Service-Token", SCHEDULE_TOKEN))
                    .andExpect(jsonPath("$.trainer").value(true));
        }

        @Test
        void unknownUserIsNotFound() throws Exception {
            mvc.perform(get("/internal/users/{id}/trainer-status", UUID.randomUUID())
                            .header("X-Service-Token", SCHEDULE_TOKEN))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        void internalRouteIsListedInOpenApiWithServiceTokenScheme() throws Exception {
            mvc.perform(get("/v3/api-docs"))
                    .andExpect(jsonPath("$.paths['/internal/users/{id}/trainer-status'].get.security[0].serviceToken")
                            .exists())
                    .andExpect(jsonPath("$.paths['/api/auth/register'].post.tags").value(hasItem("Аутентификация")));
        }
    }
}
