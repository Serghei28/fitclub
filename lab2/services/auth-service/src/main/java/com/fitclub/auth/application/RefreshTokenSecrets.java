package com.fitclub.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Случайные refresh-токены и их хеши. Клиент получает токен один раз, в БД попадает только SHA-256.
 * Медленный хеш (как для паролей) не нужен: токен — 256 случайных бит, перебор невозможен.
 */
public final class RefreshTokenSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RefreshTokenSecrets() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
