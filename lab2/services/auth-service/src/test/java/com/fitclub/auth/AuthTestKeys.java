package com.fitclub.auth;

import org.springframework.test.context.DynamicPropertyRegistry;

import com.fitclub.testsupport.TestJwt;

/** Тестовая пара ключей: auth-service подписывает закрытым ключом, проверка идёт публичным. */
final class AuthTestKeys {

    private AuthTestKeys() {
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("fitclub.security.jwt-public-key", TestJwt::publicKeyLocation);
        registry.add("fitclub.auth.jwt-private-key", TestJwt::privateKeyLocation);
    }
}
