package com.fitclub.common.security;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Пользователь из проверенного JWT. Контроллеры получают его параметром метода
 * (AuthenticatedUserArgumentResolver), поэтому user_id никогда не берётся из тела запроса.
 */
public record AuthenticatedUser(UUID id, Set<String> roles) {

    public static AuthenticatedUser from(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("User JWT authentication expected");
        }
        Set<String> roles = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .collect(Collectors.toUnmodifiableSet());
        return new AuthenticatedUser(UUID.fromString(token.getToken().getSubject()), roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole("ADMIN");
    }
}
