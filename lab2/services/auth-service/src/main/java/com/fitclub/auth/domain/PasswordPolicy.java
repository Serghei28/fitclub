package com.fitclub.auth.domain;

import java.nio.charset.StandardCharsets;

/**
 * Правила пароля.
 * BCrypt учитывает только первые 72 байта: более длинный пароль отклоняется явно,
 * иначе разные пароли с общим началом считались бы одинаковыми. Лимит считается в байтах UTF-8.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void check(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()
                || rawPassword.codePointCount(0, rawPassword.length()) < MIN_LENGTH) {
            throw AuthErrors.weakPassword(MIN_LENGTH);
        }
        if (!fitsHashLimit(rawPassword)) {
            throw AuthErrors.passwordTooLong(MAX_UTF8_BYTES);
        }
    }

    public static boolean fitsHashLimit(String rawPassword) {
        return rawPassword.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }
}
