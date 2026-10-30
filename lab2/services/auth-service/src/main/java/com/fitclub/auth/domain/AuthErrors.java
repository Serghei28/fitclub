package com.fitclub.auth.domain;

import com.fitclub.common.error.DomainException;
import com.fitclub.common.error.DomainException.Kind;

/** Бизнес-ошибки auth-service со стабильными машинными кодами. */
public final class AuthErrors {

    public static final String EMAIL_ALREADY_USED = "EMAIL_ALREADY_USED";
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN";
    public static final String INVALID_EMAIL = "INVALID_EMAIL";
    public static final String WEAK_PASSWORD = "WEAK_PASSWORD";
    public static final String PASSWORD_TOO_LONG = "PASSWORD_TOO_LONG";
    public static final String INVALID_DISPLAY_NAME = "INVALID_DISPLAY_NAME";
    public static final String ROLES_REQUIRED = "ROLES_REQUIRED";
    public static final String CANNOT_REVOKE_OWN_ADMIN = "CANNOT_REVOKE_OWN_ADMIN";

    private AuthErrors() {
    }

    public static DomainException emailAlreadyUsed() {
        return new DomainException(Kind.CONFLICT, EMAIL_ALREADY_USED, "Email уже используется",
                "Пользователь с таким email уже зарегистрирован.", "email");
    }

    /** Один ответ и для неизвестного email, и для неверного пароля: нельзя узнать, существует ли учётная запись. */
    public static DomainException invalidCredentials() {
        return new DomainException(Kind.UNAUTHORIZED, INVALID_CREDENTIALS, "Неверный вход",
                "Неверный email или пароль.");
    }

    public static DomainException invalidRefreshToken() {
        return new DomainException(Kind.UNAUTHORIZED, INVALID_REFRESH_TOKEN, "Сессия недействительна",
                "Сессия истекла или была завершена. Войдите снова.");
    }

    public static DomainException invalidEmail() {
        return new DomainException(Kind.VALIDATION, INVALID_EMAIL, "Ошибка валидации",
                "Некорректный email.", "email");
    }

    public static DomainException weakPassword(int minLength) {
        return new DomainException(Kind.VALIDATION, WEAK_PASSWORD, "Ошибка валидации",
                "Пароль должен содержать не менее " + minLength + " символов.", "password");
    }

    public static DomainException passwordTooLong(int maxBytes) {
        return new DomainException(Kind.VALIDATION, PASSWORD_TOO_LONG, "Ошибка валидации",
                "Пароль слишком длинный: не более " + maxBytes + " байт в UTF-8 (кириллица занимает 2 байта на символ).",
                "password");
    }

    public static DomainException invalidDisplayName(int maxLength) {
        return new DomainException(Kind.VALIDATION, INVALID_DISPLAY_NAME, "Ошибка валидации",
                "Имя должно содержать от 1 до " + maxLength + " символов.", "display_name");
    }

    public static DomainException rolesRequired() {
        return new DomainException(Kind.VALIDATION, ROLES_REQUIRED, "Ошибка валидации",
                "У пользователя должна быть хотя бы одна роль.", "roles");
    }

    public static DomainException cannotRevokeOwnAdmin() {
        return new DomainException(Kind.CONFLICT, CANNOT_REVOKE_OWN_ADMIN, "Действие невозможно",
                "Нельзя снять роль ADMIN с самого себя: клуб может остаться без администратора.");
    }

    public static DomainException userNotFound() {
        return new DomainException(Kind.NOT_FOUND, "RESOURCE_NOT_FOUND", "Ресурс не найден",
                "Пользователь не найден.");
    }
}
