package com.fitclub.auth.application;

import java.time.Duration;

/** Сроки действия токенов. Значения приходят из конфигурации (fitclub.auth.*). */
public record TokenSettings(Duration accessTokenTtl, Duration refreshTokenTtl) {
}
