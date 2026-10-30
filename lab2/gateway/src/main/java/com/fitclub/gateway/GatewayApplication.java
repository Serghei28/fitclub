package com.fitclub.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * API Gateway FitClub. Не входит в шесть бизнес-сервисов: маршрутизирует, проверяет токены и request id,
 * но не активирует абонементы, не считает лимиты и не координирует бизнес-сценарии.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
