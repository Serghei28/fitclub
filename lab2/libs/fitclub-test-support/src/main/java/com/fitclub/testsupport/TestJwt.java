package com.fitclub.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Выпуск тестовых JWT, подписанных тестовым RSA-ключом. Публичный ключ записывается во временный PEM-файл,
 * чтобы сервис в тесте проверял токены тем же кодом, что и в рабочем режиме.
 * Подключение: fitclub.security.jwt-public-key=TestJwt.publicKeyLocation()
 */
public final class TestJwt {

    public static final String ISSUER = "fitclub-auth";
    public static final String AUDIENCE = "fitclub-api";

    private static final KeyPair TRUSTED = generate();
    private static final KeyPair UNTRUSTED = generate();
    private static final Path PUBLIC_KEY_FILE = writePem("PUBLIC KEY", TRUSTED.getPublic().getEncoded());
    private static final Path PRIVATE_KEY_FILE = writePem("PRIVATE KEY", TRUSTED.getPrivate().getEncoded());

    private TestJwt() {
    }

    public static String publicKeyLocation() {
        return PUBLIC_KEY_FILE.toUri().toString();
    }

    /** Закрытый ключ той же пары (PKCS#8) — для тестов auth-service, который сам выпускает токены. */
    public static String privateKeyLocation() {
        return PRIVATE_KEY_FILE.toUri().toString();
    }

    /** Действительный токен пользователя с заданными ролями (CLIENT, TRAINER, ADMIN). */
    public static String token(UUID userId, String... roles) {
        return builder(userId, roles).sign();
    }

    public static Builder builder(UUID userId, String... roles) {
        return new Builder(userId, List.of(roles));
    }

    public static final class Builder {
        private final UUID userId;
        private final List<String> roles;
        private String issuer = ISSUER;
        private String audience = AUDIENCE;
        private Instant issuedAt = Instant.now();
        private Duration ttl = Duration.ofMinutes(15);
        private boolean untrustedKey;

        private Builder(UUID userId, List<String> roles) {
            this.userId = userId;
            this.roles = roles;
        }

        public Builder issuer(String value) {
            this.issuer = value;
            return this;
        }

        public Builder audience(String value) {
            this.audience = value;
            return this;
        }

        /** Токен, срок которого истёк заданное время назад (с запасом больше допустимого clock skew). */
        public Builder expiredAgo(Duration ago) {
            this.issuedAt = Instant.now().minus(ago).minus(ttl);
            return this;
        }

        /** Подпись чужим ключом: имитация поддельного токена. */
        public Builder signedByUntrustedKey() {
            this.untrustedKey = true;
            return this;
        }

        public String sign() {
            KeyPair keys = untrustedKey ? UNTRUSTED : TRUSTED;
            RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keys.getPublic())
                    .privateKey((RSAPrivateKey) keys.getPrivate()).build();
            JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
            return encoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).build(), claims())).getTokenValue();
        }

        /** Токен с симметричной подписью HS256: проверяющий сервис обязан его отклонить. */
        public String signWithHs256(byte[] secret) {
            JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(secret, "HmacSHA256")));
            return encoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).build(), claims())).getTokenValue();
        }

        private JwtClaimsSet claims() {
            return JwtClaimsSet.builder()
                    .issuer(issuer)
                    .audience(List.of(audience))
                    .subject(userId.toString())
                    .issuedAt(issuedAt)
                    .expiresAt(issuedAt.plus(ttl))
                    .id(UUID.randomUUID().toString())
                    .claim("roles", roles)
                    .build();
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writePem(String type, byte[] der) {
        try {
            Path file = Files.createTempFile("fitclub-test-jwt", ".pem");
            file.toFile().deleteOnExit();
            String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
            Files.writeString(file, "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
