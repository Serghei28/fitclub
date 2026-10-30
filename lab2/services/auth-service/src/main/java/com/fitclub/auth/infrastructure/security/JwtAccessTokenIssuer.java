package com.fitclub.auth.infrastructure.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.fitclub.auth.application.port.AccessTokenIssuer;
import com.fitclub.auth.domain.User;

/**
 * Выпуск access JWT, подписанного RS256 закрытым ключом, который есть только у auth-service.
 * Остальные сервисы проверяют подпись публичным ключом локально (fitclub-common, JwtDecoders).
 * Claims: iss, aud, sub (UUID пользователя), iat, exp, jti, roles, name.
 */
public class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final Duration ttl;

    public JwtAccessTokenIssuer(Resource privateKey, Resource publicKey, String issuer, String audience,
            Duration ttl) {
        RSAPrivateKey rsaPrivate = read(privateKey, RsaKeyConverters.pkcs8());
        RSAPublicKey rsaPublic = read(publicKey, RsaKeyConverters.x509());
        // Ошибка конфигурации обнаруживается при запуске, а не тем, что все токены вдруг станут недействительными
        if (rsaPrivate instanceof RSAPrivateCrtKey crt && !crt.getModulus().equals(rsaPublic.getModulus())) {
            throw new IllegalStateException("JWT private key does not match the configured public key");
        }
        RSAKey jwk = new RSAKey.Builder(rsaPublic).privateKey(rsaPrivate).build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        this.issuer = issuer;
        this.audience = audience;
        this.ttl = ttl;
    }

    @Override
    public IssuedAccessToken issue(User user, Instant now) {
        Instant expiresAt = now.plus(ttl);
        List<String> roles = user.roles().stream().map(Enum::name).sorted().toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("roles", roles)
                .claim("name", user.displayName())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
                claims)).getTokenValue();
        return new IssuedAccessToken(token, expiresAt);
    }

    private static <K> K read(Resource resource, org.springframework.core.convert.converter.Converter<InputStream, K> converter) {
        try (InputStream in = resource.getInputStream()) {
            return converter.convert(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read JWT key from " + resource.getDescription(), e);
        }
    }
}
