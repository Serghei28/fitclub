package com.fitclub.auth.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.fitclub.auth.application.TokenSettings;
import com.fitclub.auth.application.port.AccessTokenIssuer;
import com.fitclub.auth.infrastructure.security.JwtAccessTokenIssuer;
import com.fitclub.common.security.FitClubSecurityProperties;

/** Сборка адаптеров auth-service: application получает только порты (DIP). */
@Configuration(proxyBeanMethods = false)
class AuthInfrastructureConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    TokenSettings tokenSettings(AuthProperties properties) {
        return new TokenSettings(properties.accessTokenTtl(), properties.refreshTokenTtl());
    }

    /** iss, aud и публичный ключ те же, по которым токен проверяют все сервисы (fitclub.security.*). */
    @Bean
    AccessTokenIssuer accessTokenIssuer(AuthProperties auth, FitClubSecurityProperties security) {
        return new JwtAccessTokenIssuer(auth.jwtPrivateKey(), security.jwtPublicKey(), security.issuer(),
                security.audience(), auth.accessTokenTtl());
    }
}
