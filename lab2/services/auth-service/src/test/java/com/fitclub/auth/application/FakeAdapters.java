package com.fitclub.auth.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fitclub.auth.application.port.AccessTokenIssuer;
import com.fitclub.auth.application.port.PasswordHasher;
import com.fitclub.auth.application.port.RefreshTokenRepository;
import com.fitclub.auth.application.port.UserRepository;
import com.fitclub.auth.domain.AuthErrors;
import com.fitclub.auth.domain.Email;
import com.fitclub.auth.domain.PasswordPolicy;
import com.fitclub.auth.domain.RefreshToken;
import com.fitclub.auth.domain.Role;
import com.fitclub.auth.domain.User;
import com.fitclub.common.web.PageResponse;

/**
 * Простые реализации портов для unit-тестов use case'ов: без Spring, БД и криптографии.
 * То, что сценарии работают с ними без изменений, и есть проверка DIP: application зависит от портов.
 */
final class FakeAdapters {

    private FakeAdapters() {
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class FakePasswordHasher implements PasswordHasher {
        @Override
        public String hash(String rawPassword) {
            return "hashed:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String passwordHash) {
            return rawPassword != null && PasswordPolicy.fitsHashLimit(rawPassword)
                    && passwordHash.equals("hashed:" + rawPassword);
        }
    }

    static final class FakeAccessTokenIssuer implements AccessTokenIssuer {
        private final Duration ttl;

        FakeAccessTokenIssuer(Duration ttl) {
            this.ttl = ttl;
        }

        @Override
        public IssuedAccessToken issue(User user, Instant now) {
            List<String> roles = user.roles().stream().map(Enum::name).sorted().toList();
            return new IssuedAccessToken("access:" + user.id() + ":" + String.join(",", roles), now.plus(ttl));
        }
    }

    static final class InMemoryUserRepository implements UserRepository {
        final Map<UUID, User> users = new LinkedHashMap<>();

        @Override
        public Optional<User> findById(UUID id) {
            return Optional.ofNullable(users.get(id));
        }

        @Override
        public Optional<User> findByEmail(Email email) {
            return users.values().stream().filter(u -> u.email().equals(email)).findFirst();
        }

        @Override
        public boolean existsWithRole(Role role) {
            return users.values().stream().anyMatch(u -> u.hasRole(role));
        }

        @Override
        public void add(User user) {
            if (findByEmail(user.email()).isPresent()) {
                throw AuthErrors.emailAlreadyUsed();
            }
            users.put(user.id(), user);
        }

        @Override
        public void update(User user) {
            users.put(user.id(), user);
        }

        @Override
        public PageResponse<User> search(Role role, String text, int page, int size) {
            List<User> all = users.values().stream()
                    .filter(u -> role == null || u.hasRole(role))
                    .sorted(Comparator.comparing(u -> u.email().value()))
                    .toList();
            return PageResponse.of(all.stream().skip((long) page * size).limit(size).toList(), page, size,
                    all.size());
        }
    }

    static final class InMemoryRefreshTokenRepository implements RefreshTokenRepository {
        final Map<UUID, RefreshToken> tokens = new LinkedHashMap<>();

        @Override
        public Optional<RefreshToken> findByHashForUpdate(String tokenHash) {
            return tokens.values().stream().filter(t -> t.tokenHash().equals(tokenHash)).findFirst();
        }

        @Override
        public void add(RefreshToken token) {
            tokens.put(token.id(), token);
        }

        @Override
        public void update(RefreshToken token) {
            tokens.put(token.id(), token);
        }

        @Override
        public int revokeFamily(UUID familyId, Instant now) {
            List<RefreshToken> active = new ArrayList<>(tokens.values().stream()
                    .filter(t -> t.familyId().equals(familyId) && !t.isRevoked()).toList());
            active.forEach(t -> t.revoke(now));
            return active.size();
        }

        long activeCount(UUID familyId) {
            return tokens.values().stream().filter(t -> t.familyId().equals(familyId) && !t.isRevoked()).count();
        }
    }
}
