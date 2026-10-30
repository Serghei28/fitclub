package com.fitclub.notification.infrastructure.config;

import java.time.Duration;
import java.time.ZoneId;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import com.fitclub.notification.application.NotificationEventHandler.ReminderSettings;
import com.fitclub.notification.application.ReminderProcessor;
import com.fitclub.notification.domain.NotificationTexts;

/** Настройки и сборка notification-service, фоновый запуск обработки напоминаний. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationConfiguration.NotificationProperties.class)
class NotificationConfiguration {

    /**
     * @param displayZone  часовой пояс в текстах уведомлений (Europe/Chisinau)
     * @param reminderLead за сколько до начала напомнить (REMINDER_LEAD_MINUTES)
     * @param reminders    фоновая обработка задач напоминаний
     */
    @Validated
    @ConfigurationProperties("fitclub.notification")
    record NotificationProperties(@NotNull @DefaultValue("Europe/Chisinau") ZoneId displayZone,
            @NotNull @DefaultValue("60m") Duration reminderLead, @NotNull @Valid Reminders reminders) {

        record Reminders(@DefaultValue("true") boolean schedulerEnabled, @Min(1) @DefaultValue("50") int batchSize) {
        }
    }

    @Bean
    NotificationTexts notificationTexts(NotificationProperties properties) {
        return new NotificationTexts(properties.displayZone());
    }

    @Bean
    ReminderSettings reminderSettings(NotificationProperties properties) {
        return new ReminderSettings(properties.reminderLead());
    }

    /** Периодический запуск; в тестах выключается, и время управляется через Clock. */
    @Component
    @ConditionalOnProperty(prefix = "fitclub.notification.reminders", name = "scheduler-enabled", havingValue = "true",
            matchIfMissing = true)
    static class ReminderScheduler {

        private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

        private final ReminderProcessor processor;
        private final NotificationProperties properties;

        ReminderScheduler(ReminderProcessor processor, NotificationProperties properties) {
            this.processor = processor;
            this.properties = properties;
        }

        @Scheduled(fixedDelayString = "${fitclub.notification.reminders.poll-interval:15s}", initialDelayString = "5s")
        void tick() {
            try {
                processor.processDue(properties.reminders().batchSize());
            } catch (RuntimeException e) {
                // Задачи остаются в БД и будут обработаны на следующем проходе
                log.warn("Reminder processing failed, will retry: {}", e.getClass().getSimpleName());
            }
        }
    }
}
