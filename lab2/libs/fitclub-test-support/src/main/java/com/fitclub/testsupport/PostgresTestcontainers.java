package com.fitclub.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Настоящий PostgreSQL той же версии, что и в docker-compose.yml, для интеграционных тестов.
 * Тестовая БД изолирована от пользовательских данных в Docker volume.
 * Подключение: @Import(PostgresTestcontainers.class).
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainers {

    public static final String IMAGE = "postgres:17.11-alpine";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(IMAGE);
    }
}
