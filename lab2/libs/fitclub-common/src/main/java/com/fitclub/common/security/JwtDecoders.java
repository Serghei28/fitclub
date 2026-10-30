package com.fitclub.common.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Objects;

import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Локальная проверка пользовательских JWT по публичному ключу auth-service, без HTTP-запроса в Auth.
 * Проверяются: подпись, алгоритм (только RS256), iss, aud, наличие и срок exp, nbf, наличие sub.
 */
public final class JwtDecoders {

    private JwtDecoders() {
    }

    public static JwtDecoder rs256(Resource publicKey, String issuer, String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(readPublicKey(publicKey))
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
                new JwtIssuerValidator(issuer),
                new JwtAudienceValidator(audience),
                // JwtTimestampValidator пропускает токен без exp, поэтому наличие exp проверяется явно
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank())));
        return decoder;
    }

    static RSAPublicKey readPublicKey(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return RsaKeyConverters.x509().convert(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read JWT public key from " + resource.getDescription(), e);
        }
    }
}
