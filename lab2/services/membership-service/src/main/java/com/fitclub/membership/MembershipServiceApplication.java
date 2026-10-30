package com.fitclub.membership;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** FitClub Membership Service: самостоятельное Spring Boot приложение со своей БД membership_db. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MembershipServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MembershipServiceApplication.class, args);
    }
}
