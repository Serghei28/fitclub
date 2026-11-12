package com.fitclub.e2e.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Управление изолированным Compose-проектом стенда (по умолчанию fitclub-e2e): остановка и запуск
 * контейнеров для сценариев сбоев и чтение служебных таблиц через psql внутри контейнера postgres.
 * Пользовательский стенд (проект fitclub) эти тесты не трогают.
 */
public final class Stack {

    private final String project;
    private final Path projectDir;

    public Stack(String project, Path projectDir) {
        this.project = project;
        this.projectDir = projectDir;
    }

    public void stop(String service) {
        compose("stop", service);
    }

    public void start(String service) {
        compose("start", service);
    }

    /** Одно значение из БД сервиса (psql -tA), например count(*). */
    public String sql(String database, String query) {
        return compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", database, "-tA", "-c", query).trim();
    }

    private String compose(String... args) {
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-p", project,
                "-f", projectDir.resolve("docker-compose.yml").toString()));
        command.addAll(List.of(args));
        try {
            Path output = Files.createTempFile("fitclub-e2e-", ".log");
            Process process = new ProcessBuilder(command)
                    .directory(projectDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            if (!process.waitFor(3, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("Команда не завершилась за 3 минуты: " + String.join(" ", args));
            }
            String text = Files.readString(output, StandardCharsets.UTF_8);
            Files.deleteIfExists(output);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("docker compose " + String.join(" ", args) + " → "
                        + process.exitValue() + ": " + text);
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось запустить docker compose: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
