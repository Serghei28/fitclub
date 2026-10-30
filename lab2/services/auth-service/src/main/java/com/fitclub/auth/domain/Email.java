package com.fitclub.auth.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Нормализованный email: без пробелов по краям и в нижнем регистре.
 * Уникальность в БД проверяется по нормализованному значению (constraint uq_users_email),
 * поэтому «User@Mail.com» и « user@mail.com » — один и тот же пользователь.
 */
public record Email(String value) {

    public static final int MAX_LENGTH = 254;
    private static final Pattern FORMAT = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public Email {
        if (value == null || value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()
                || !value.equals(normalize(value))) {
            throw AuthErrors.invalidEmail();
        }
    }

    public static Email of(String raw) {
        if (raw == null) {
            throw AuthErrors.invalidEmail();
        }
        return new Email(normalize(raw));
    }

    private static String normalize(String raw) {
        return raw.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return value;
    }
}
