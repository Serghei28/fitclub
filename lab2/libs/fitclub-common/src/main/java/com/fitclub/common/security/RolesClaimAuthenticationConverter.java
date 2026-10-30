package com.fitclub.common.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Отображение claim roles из JWT в authorities Spring Security: CLIENT → ROLE_CLIENT и т.д.
 * Неизвестные значения отбрасываются: роль не может появиться из-за опечатки или подмены claim.
 * Имя principal — sub (UUID пользователя).
 */
public class RolesClaimAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String ROLES_CLAIM = "roles";
    static final Set<String> KNOWN_ROLES = Set.of("CLIENT", "TRAINER", "ADMIN");

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject());
    }

    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .filter(KNOWN_ROLES::contains)
                .distinct()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
