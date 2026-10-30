package com.fitclub.messaging;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

/** Настройки RabbitMQ по умолчанию (publisher confirms, returns, группа health messaging), низший приоритет. */
public class MessagingDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "fitclubMessagingDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        try {
            environment.getPropertySources().addLast(new ResourcePropertySource(SOURCE_NAME,
                    new ClassPathResource("fitclub-messaging-defaults.properties")));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load fitclub-messaging-defaults.properties", e);
        }
    }
}
