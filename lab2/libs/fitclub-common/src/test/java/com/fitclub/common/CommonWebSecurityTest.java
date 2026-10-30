package com.fitclub.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fitclub.common.testapp.TestApplication;
import com.fitclub.testsupport.TestJwt;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class CommonWebSecurityTest {

    private static final String PAYMENT_TOKEN = "payment-token-0123456789-0123456789-abcdef";
    private static final String UUID_PATTERN = "^[0-9a-f-]{36}$";

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void jwtKey(DynamicPropertyRegistry registry) {
        registry.add("fitclub.security.jwt-public-key", TestJwt::publicKeyLocation);
    }

    private ResultActions getWithToken(String path, String token) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static void assertProblem(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type").value(containsString("https://fitclub.local/problems/")))
                .andExpect(jsonPath("$.request_id").value(matchesPattern("^[A-Za-z0-9-]{8,64}$")));
    }

    @Nested
    class UserJwt {

        @Test
        void missingTokenIsRejectedWithProblemJson() throws Exception {
            ResultActions result = mvc.perform(get("/api/me"));
            assertProblem(result, 401, "AUTHENTICATION_REQUIRED");
            result.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        }

        @Test
        void validTokenAuthenticatesUserFromSubAndRoles() throws Exception {
            UUID userId = UUID.randomUUID();
            getWithToken("/api/me", TestJwt.token(userId, "CLIENT", "TRAINER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(userId.toString()))
                    .andExpect(jsonPath("$.roles").value(hasItems("CLIENT", "TRAINER")));
        }

        @Test
        void unknownRoleClaimIsIgnored() throws Exception {
            getWithToken("/api/me", TestJwt.token(UUID.randomUUID(), "CLIENT", "SUPERUSER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles.length()").value(1));
        }

        @Test
        void expiredTokenIsRejected() throws Exception {
            String token = TestJwt.builder(UUID.randomUUID(), "CLIENT").expiredAgo(Duration.ofMinutes(5)).sign();
            assertProblem(getWithToken("/api/me", token), 401, "INVALID_TOKEN");
        }

        @Test
        void tokenSignedByForeignKeyIsRejected() throws Exception {
            String token = TestJwt.builder(UUID.randomUUID(), "ADMIN").signedByUntrustedKey().sign();
            assertProblem(getWithToken("/api/me", token), 401, "INVALID_TOKEN");
        }

        @Test
        void wrongIssuerIsRejected() throws Exception {
            String token = TestJwt.builder(UUID.randomUUID(), "CLIENT").issuer("evil-issuer").sign();
            assertProblem(getWithToken("/api/me", token), 401, "INVALID_TOKEN");
        }

        @Test
        void wrongAudienceIsRejected() throws Exception {
            String token = TestJwt.builder(UUID.randomUUID(), "CLIENT").audience("other-api").sign();
            assertProblem(getWithToken("/api/me", token), 401, "INVALID_TOKEN");
        }

        @Test
        void symmetricAlgorithmIsRejected() throws Exception {
            byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
            String token = TestJwt.builder(UUID.randomUUID(), "ADMIN").signWithHs256(secret);
            assertProblem(getWithToken("/api/me", token), 401, "INVALID_TOKEN");
        }

        @Test
        void methodSecurityDeniesMissingRoleWithProblemJson() throws Exception {
            assertProblem(getWithToken("/api/admin-only", TestJwt.token(UUID.randomUUID(), "CLIENT")),
                    403, "ACCESS_DENIED");
            getWithToken("/api/admin-only", TestJwt.token(UUID.randomUUID(), "ADMIN")).andExpect(status().isOk());
        }

        @Test
        void publicPathDoesNotRequireToken() throws Exception {
            mvc.perform(get("/api/public/ping")).andExpect(status().isOk());
        }

        @Test
        void technicalEndpointsArePublic() throws Exception {
            mvc.perform(get("/health")).andExpect(status().isOk());
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        }
    }

    @Nested
    class Errors {

        private final String token = TestJwt.token(UUID.randomUUID(), "CLIENT");

        @Test
        void validationErrorListsFieldsInSnakeCase() throws Exception {
            ResultActions result = mvc.perform(post("/api/echo")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"contact_email\":\"not-an-email\",\"visit_limit\":0}"));
            assertProblem(result, 400, "VALIDATION_ERROR");
            result.andExpect(jsonPath("$.field_errors[*].field").value(hasItems("contact_email", "visit_limit")));
        }

        @Test
        void malformedJsonIsBadRequest() throws Exception {
            assertProblem(mvc.perform(post("/api/echo")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{broken")), 400, "MALFORMED_REQUEST");
        }

        @Test
        void businessConflictKeepsItsCode() throws Exception {
            assertProblem(getWithToken("/api/conflict", token), 409, "SESSION_FULL");
        }

        @Test
        void unexpectedErrorHidesInternalDetails() throws Exception {
            ResultActions result = getWithToken("/api/boom", token);
            assertProblem(result, 500, "INTERNAL_ERROR");
            result.andExpect(content().string(not(containsString("jdbc:"))))
                    .andExpect(content().string(not(containsString("IllegalStateException"))))
                    .andExpect(content().string(not(containsString("at com.fitclub"))));
        }

        @Test
        void databaseFailureIsServiceUnavailable() throws Exception {
            ResultActions result = getWithToken("/api/db-down", token);
            assertProblem(result, 503, "DATABASE_UNAVAILABLE");
            result.andExpect(content().string(not(containsString("postgres:5432"))));
        }

        @Test
        void containerErrorPageUsesSameProblemFormat() throws Exception {
            // Так контейнер вызывает /error после sendError (например, после отказа файрвола Spring Security)
            ResultActions result = mvc.perform(get("/error")
                    .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 400)
                    .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/internal/users//x")
                    // request_id исходного запроса: при error-dispatch MDC уже очищен, значение берётся из атрибута
                    .requestAttr(com.fitclub.common.web.RequestIds.ATTRIBUTE, "original-request-01"));
            assertProblem(result, 400, "MALFORMED_REQUEST");
            result.andExpect(jsonPath("$.instance").value("/internal/users//x"))
                    .andExpect(jsonPath("$.request_id").value("original-request-01"));
        }

        @Test
        void unknownPathIsNotFoundForAuthenticatedUser() throws Exception {
            assertProblem(getWithToken("/api/does-not-exist", token), 404, "RESOURCE_NOT_FOUND");
        }
    }

    @Nested
    class RequestId {

        @Test
        void validIncomingRequestIdIsKept() throws Exception {
            mvc.perform(get("/api/public/ping").header("X-Request-ID", "client-req-12345"))
                    .andExpect(header().string("X-Request-ID", "client-req-12345"));
        }

        @Test
        void invalidIncomingRequestIdIsReplaced() throws Exception {
            mvc.perform(get("/api/public/ping").header("X-Request-ID", "bad id;drop"))
                    .andExpect(header().string("X-Request-ID", matchesPattern(UUID_PATTERN)));
        }

        @Test
        void problemBodyCarriesSameRequestIdAsHeader() throws Exception {
            mvc.perform(get("/api/me").header("X-Request-ID", "trace-abcdef-0001"))
                    .andExpect(header().string("X-Request-ID", "trace-abcdef-0001"))
                    .andExpect(jsonPath("$.request_id").value("trace-abcdef-0001"));
        }
    }

    @Nested
    class InternalServiceToken {

        @Test
        void missingServiceTokenIsRejected() throws Exception {
            assertProblem(mvc.perform(get("/internal/orders/42")), 401, "INVALID_SERVICE_CREDENTIAL");
        }

        @Test
        void wrongServiceTokenIsRejected() throws Exception {
            assertProblem(mvc.perform(get("/internal/orders/42").header("X-Service-Token", PAYMENT_TOKEN + "x")),
                    401, "INVALID_SERVICE_CREDENTIAL");
        }

        @Test
        void userJwtDoesNotOpenInternalRoutes() throws Exception {
            assertProblem(getWithToken("/internal/orders/42", TestJwt.token(UUID.randomUUID(), "ADMIN")),
                    401, "INVALID_SERVICE_CREDENTIAL");
        }

        @Test
        void callerReachesOnlyItsOwnRoutes() throws Exception {
            mvc.perform(get("/internal/orders/42").header("X-Service-Token", PAYMENT_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.order").value("42"));
            assertProblem(mvc.perform(get("/internal/eligibility/check").header("X-Service-Token", PAYMENT_TOKEN)),
                    403, "ACCESS_DENIED");
        }
    }
}
