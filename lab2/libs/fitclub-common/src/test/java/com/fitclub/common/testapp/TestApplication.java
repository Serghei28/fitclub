package com.fitclub.common.testapp;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fitclub.common.error.ApiException;
import com.fitclub.common.security.AuthenticatedUser;

/** Минимальное приложение для проверки общего модуля так, как его используют сервисы. */
@SpringBootApplication
public class TestApplication {

    @RestController
    static class TestController {

        @GetMapping("/api/me")
        Map<String, Object> me(AuthenticatedUser user) {
            return Map.of("id", user.id().toString(), "roles", user.roles());
        }

        @GetMapping("/api/admin-only")
        @PreAuthorize("hasRole('ADMIN')")
        Map<String, String> adminOnly() {
            return Map.of("ok", "admin");
        }

        @PostMapping("/api/echo")
        EchoRequest echo(@Valid @RequestBody EchoRequest request) {
            return request;
        }

        @GetMapping("/api/conflict")
        void conflict() {
            throw ApiException.conflict("SESSION_FULL", "Мест нет", "На занятие больше нет свободных мест.");
        }

        @GetMapping("/api/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail jdbc:postgresql://db:5432");
        }

        @GetMapping("/api/db-down")
        void dbDown() {
            throw new DataAccessResourceFailureException("Connection refused: postgres:5432");
        }

        @GetMapping("/api/public/ping")
        Map<String, String> ping() {
            return Map.of("status", HttpStatus.OK.name());
        }

        @GetMapping("/internal/orders/{id}")
        Map<String, String> internalOrder(@PathVariable String id) {
            return Map.of("order", id);
        }

        @GetMapping("/internal/eligibility/check")
        Map<String, String> internalEligibility() {
            return Map.of("eligible", "true");
        }
    }

    record EchoRequest(@NotBlank @Email String contactEmail, @Positive int visitLimit) {
    }
}
