package com.fitclub.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fitclub.testsupport.PostgresTestcontainers;

/**
 * Потеря собственной БД: /ready сообщает о неготовности (503), а /health (liveness процесса) остаётся UP.
 * Механизм health общий для всех сервисов (fitclub-common), поэтому проверяется на одном сервисе.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestcontainers.class)
@DirtiesContext
class DatabaseOutageIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    @DynamicPropertySource
    static void jwtKey(DynamicPropertyRegistry registry) {
        AuthTestKeys.register(registry);
    }

    @Test
    void readinessFailsWhileLivenessStaysUpWhenDatabaseIsLost() throws Exception {
        mvc.perform(get("/ready")).andExpect(status().isOk());

        postgres.stop();

        mvc.perform(get("/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.db.status").value("DOWN"));
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
