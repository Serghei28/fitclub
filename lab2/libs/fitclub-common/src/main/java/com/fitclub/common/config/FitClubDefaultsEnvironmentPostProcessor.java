package com.fitclub.common.config;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

/**
 * Добавляет технические настройки FitClub (логи, health, формат JSON) как источник с низшим приоритетом.
 * Выполняется до инициализации логирования, поэтому JSON-формат действует с первой строки лога.
 */
public class FitClubDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "fitclubDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        try {
            ResourcePropertySource defaults =
                    new ResourcePropertySource(SOURCE_NAME, new ClassPathResource("fitclub-defaults.properties"));
            environment.getPropertySources().addLast(defaults);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load fitclub-defaults.properties", e);
        }
    }
}
