package com.fitclub.schedule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** FitClub Schedule Service: самостоятельное Spring Boot приложение со своей БД schedule_db. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ScheduleServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScheduleServiceApplication.class, args);
    }
}
