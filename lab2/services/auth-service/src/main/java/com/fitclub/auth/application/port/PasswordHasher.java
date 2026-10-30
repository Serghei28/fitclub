package com.fitclub.auth.application.port;

/** Порт хеширования паролей. Реализация использует стандартный Spring PasswordEncoder (BCrypt). */
public interface PasswordHasher {

    String hash(String rawPassword);

    /** false для любого несовпадения, включая пароль длиннее лимита алгоритма; исключений не бросает. */
    boolean matches(String rawPassword, String passwordHash);
}
