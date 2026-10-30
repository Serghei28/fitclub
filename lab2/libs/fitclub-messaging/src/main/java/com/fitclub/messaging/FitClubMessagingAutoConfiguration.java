package com.fitclub.messaging;

import java.time.Clock;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

/** Outbox writer, relay и индикатор доставки для сервисов, которые публикуют события. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class FitClubMessagingAutoConfiguration {

    @Bean
    OutboxWriter outboxWriter(JdbcTemplate jdbc, JsonMapper jsonMapper, Clock clock,
            @Value("${spring.application.name}") String producer) {
        return new OutboxWriter(jdbc, jsonMapper, clock, producer);
    }

    @Bean
    @ConditionalOnBean(RabbitTemplate.class)
    OutboxRelay outboxRelay(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, RabbitTemplate rabbit,
            OutboxProperties properties, Clock clock) {
        return new OutboxRelay(jdbc, new TransactionTemplate(transactionManager), rabbit, properties, clock);
    }

    @Bean
    OutboxHealthIndicator outboxHealthIndicator(JdbcTemplate jdbc, OutboxProperties properties, Clock clock) {
        return new OutboxHealthIndicator(jdbc, properties, clock);
    }
}
