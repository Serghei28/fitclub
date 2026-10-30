package com.fitclub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.fitclub.auth.domain.Email;
import com.fitclub.auth.domain.Role;
import com.fitclub.auth.domain.User;
import com.fitclub.auth.infrastructure.security.JwtAccessTokenIssuer;
import com.fitclub.common.security.JwtDecoders;
import com.fitclub.testsupport.TestJwt;

/** Токен auth-service принимается тем же проверяющим кодом, что используют все остальные сервисы. */
class JwtAccessTokenIssuerTest {

    private static final DefaultResourceLoader LOADER = new DefaultResourceLoader();
    private final Resource privateKey = LOADER.getResource(TestJwt.privateKeyLocation());
    private final Resource publicKey = LOADER.getResource(TestJwt.publicKeyLocation());

    @Test
    void issuedTokenIsAcceptedByCommonDecoderWithRolesAndExpiry() {
        Instant now = Instant.now();
        User user = User.restore(UUID.randomUUID(), Email.of("t@fitclub.md"), "hash", "Тренер Иван", true,
                EnumSet.of(Role.TRAINER, Role.CLIENT), now, now, 0);
        JwtAccessTokenIssuer issuer = new JwtAccessTokenIssuer(privateKey, publicKey, TestJwt.ISSUER,
                TestJwt.AUDIENCE, Duration.ofMinutes(15));

        String token = issuer.issue(user, now).value();
        JwtDecoder decoder = JwtDecoders.rs256(publicKey, TestJwt.ISSUER, TestJwt.AUDIENCE);
        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo(user.id().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CLIENT", "TRAINER");
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(15)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
    }

    @Test
    void mismatchedKeyPairIsDetectedAtStartup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        Path otherPublic = Files.createTempFile("other-public", ".pem");
        otherPublic.toFile().deleteOnExit();
        Files.writeString(otherPublic, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");

        assertThatThrownBy(() -> new JwtAccessTokenIssuer(privateKey,
                LOADER.getResource(otherPublic.toUri().toString()), TestJwt.ISSUER, TestJwt.AUDIENCE,
                Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
    }
}
