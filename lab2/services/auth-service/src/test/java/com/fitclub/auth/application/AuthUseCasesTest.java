package com.fitclub.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fitclub.auth.application.FakeAdapters.FakeAccessTokenIssuer;
import com.fitclub.auth.application.FakeAdapters.FakePasswordHasher;
import com.fitclub.auth.application.FakeAdapters.InMemoryRefreshTokenRepository;
import com.fitclub.auth.application.FakeAdapters.InMemoryUserRepository;
import com.fitclub.auth.application.FakeAdapters.MutableClock;
import com.fitclub.auth.domain.AuthErrors;
import com.fitclub.auth.domain.Role;
import com.fitclub.auth.domain.User;
import com.fitclub.common.error.ApiException;
import com.fitclub.common.error.DomainException;

/** Правила auth-service на fake-адаптерах: без Spring-контекста, веб-сервера и БД. */
class AuthUseCasesTest {

    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    private MutableClock clock;
    private InMemoryUserRepository users;
    private InMemoryRefreshTokenRepository refreshTokens;
    private RegisterUserUseCase register;
    private LoginUseCase login;
    private RefreshSessionUseCase refresh;
    private LogoutUseCase logout;
    private ChangeUserRolesUseCase changeRoles;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-02T10:00:00Z"));
        users = new InMemoryUserRepository();
        refreshTokens = new InMemoryRefreshTokenRepository();
        FakePasswordHasher hasher = new FakePasswordHasher();
        SessionIssuer sessions = new SessionIssuer(new FakeAccessTokenIssuer(ACCESS_TTL), refreshTokens,
                new TokenSettings(ACCESS_TTL, REFRESH_TTL));
        register = new RegisterUserUseCase(users, hasher, clock);
        login = new LoginUseCase(users, hasher, sessions, clock);
        refresh = new RefreshSessionUseCase(refreshTokens, users, sessions, clock);
        logout = new LogoutUseCase(refreshTokens, clock);
        changeRoles = new ChangeUserRolesUseCase(users, clock);
    }

    private User registerClient(String email) {
        return register.register(new RegisterUserUseCase.Command(email, "S3cure-pass", "Анна"));
    }

    private static void assertCode(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOf(DomainException.class);
        assertThat(((DomainException) thrown).code()).isEqualTo(code);
    }

    @Nested
    class Registration {

        @Test
        void assignsOnlyClientRoleAndNormalizesEmail() {
            User user = registerClient("  Anna@Example.COM ");
            assertThat(user.roles()).containsExactly(Role.CLIENT);
            assertThat(user.email().value()).isEqualTo("anna@example.com");
            assertThat(user.passwordHash()).isNotEqualTo("S3cure-pass");
        }

        @Test
        void sameEmailInDifferentCaseIsDuplicate() {
            registerClient("anna@example.com");
            assertThatThrownBy(() -> registerClient("ANNA@example.com"))
                    .satisfies(e -> assertCode(e, AuthErrors.EMAIL_ALREADY_USED));
        }

        @Test
        void rejectsWeakPassword() {
            assertThatThrownBy(() -> register.register(new RegisterUserUseCase.Command("a@b.md", "short", "A")))
                    .satisfies(e -> assertCode(e, AuthErrors.WEAK_PASSWORD));
        }
    }

    @Nested
    class Login {

        @Test
        void issuesTokenPairForValidCredentials() {
            User user = registerClient("anna@example.com");
            TokenPair pair = login.login("ANNA@example.com", "S3cure-pass");
            assertThat(pair.user().id()).isEqualTo(user.id());
            assertThat(pair.accessTokenExpiresAt()).isEqualTo(clock.instant().plus(ACCESS_TTL));
            assertThat(pair.refreshTokenExpiresAt()).isEqualTo(clock.instant().plus(REFRESH_TTL));
            // В хранилище только хеш, не сам refresh-токен
            assertThat(refreshTokens.tokens.values()).allSatisfy(t ->
                    assertThat(t.tokenHash()).isNotEqualTo(pair.refreshToken()).hasSize(64));
        }

        @Test
        void wrongPasswordAndUnknownEmailGiveSameError() {
            registerClient("anna@example.com");
            assertThatThrownBy(() -> login.login("anna@example.com", "wrong-password"))
                    .satisfies(e -> assertCode(e, AuthErrors.INVALID_CREDENTIALS));
            assertThatThrownBy(() -> login.login("nobody@example.com", "S3cure-pass"))
                    .satisfies(e -> assertCode(e, AuthErrors.INVALID_CREDENTIALS));
            assertThatThrownBy(() -> login.login("not-an-email", "S3cure-pass"))
                    .satisfies(e -> assertCode(e, AuthErrors.INVALID_CREDENTIALS));
        }

        @Test
        void overlongPasswordIsRejectedWithoutException() {
            registerClient("anna@example.com");
            assertThatThrownBy(() -> login.login("anna@example.com", "я".repeat(40)))
                    .satisfies(e -> assertCode(e, AuthErrors.INVALID_CREDENTIALS));
        }
    }

    @Nested
    class RefreshAndLogout {

        @Test
        void rotationInvalidatesPreviousRefreshToken() {
            registerClient("anna@example.com");
            TokenPair first = login.login("anna@example.com", "S3cure-pass");

            TokenPair second = refresh.refresh(first.refreshToken());

            assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
            assertThatThrownBy(() -> refresh.refresh(first.refreshToken()))
                    .satisfies(e -> assertCode(e, AuthErrors.INVALID_REFRESH_TOKEN));
        }

        @Test
        void reuseOfRotatedTokenRevokesWholeFamily() {
            registerClient("anna@example.com");
            TokenPair first = login.login("anna@example.com", "S3cure-pass");
            TokenPair second = refresh.refresh(first.refreshToken());
            UUID family = refreshTokens.tokens.values().iterator().next().familyId();

            assertThatThrownBy(() -> refresh.refresh(first.refreshToken())).isInstanceOf(DomainException.class);

            assertThat(refreshTokens.activeCount(family)).isZero();
            assertThatThrownBy(() -> refresh.refresh(second.refreshToken()))
                    .satisfies(e -> assertCode(e, AuthErrors.INVALID_REFRESH_TOKEN));
        }

        @Test
        void refreshTokenExpiresExactlyAtExpiryInstant() {
            registerClient("anna@example.com");
            TokenPair pair = login.login("anna@example.com", "S3cure-pass");

            clock.set(pair.refreshTokenExpiresAt().minusSeconds(1));
            TokenPair stillValid = refresh.refresh(pair.refreshToken());

            clock.set(stillValid.refreshTokenExpiresAt());
            assertThatThrownBy(() -> refresh.refresh(stillValid.refreshToken()))
                    .satisfies(e -> assertCode(e, AuthErrors.INVALID_REFRESH_TOKEN));
        }

        @Test
        void refreshPicksUpChangedRoles() {
            User admin = adminUser();
            User client = registerClient("anna@example.com");
            TokenPair pair = login.login("anna@example.com", "S3cure-pass");

            changeRoles.changeRoles(admin.id(), Set.of("ADMIN"), client.id(), EnumSet.of(Role.CLIENT, Role.TRAINER));

            assertThat(refresh.refresh(pair.refreshToken()).accessToken()).endsWith(":CLIENT,TRAINER");
        }

        @Test
        void logoutRevokesSessionAndIsIdempotent() {
            registerClient("anna@example.com");
            TokenPair pair = login.login("anna@example.com", "S3cure-pass");

            logout.logout(pair.refreshToken());
            logout.logout(pair.refreshToken());
            logout.logout("unknown-token");

            assertThatThrownBy(() -> refresh.refresh(pair.refreshToken()))
                    .satisfies(e -> assertCode(e, AuthErrors.INVALID_REFRESH_TOKEN));
        }
    }

    @Nested
    class Roles {

        @Test
        void adminAssignsTrainerRole() {
            User admin = adminUser();
            User client = registerClient("anna@example.com");
            User updated = changeRoles.changeRoles(admin.id(), Set.of("ADMIN"), client.id(),
                    EnumSet.of(Role.CLIENT, Role.TRAINER));
            assertThat(updated.roles()).containsExactlyInAnyOrder(Role.CLIENT, Role.TRAINER);
            assertThat(updated.isActiveTrainer()).isTrue();
        }

        @Test
        void nonAdminCannotAssignRolesEvenIfCalledDirectly() {
            User client = registerClient("anna@example.com");
            assertThatThrownBy(() -> changeRoles.changeRoles(client.id(), Set.of("CLIENT"), client.id(),
                    EnumSet.of(Role.ADMIN))).isInstanceOf(ApiException.class);
        }

        @Test
        void adminCannotRemoveOwnAdminRole() {
            User admin = adminUser();
            assertThatThrownBy(() -> changeRoles.changeRoles(admin.id(), Set.of("ADMIN"), admin.id(),
                    EnumSet.of(Role.CLIENT))).satisfies(e -> assertCode(e, AuthErrors.CANNOT_REVOKE_OWN_ADMIN));
        }

        @Test
        void emptyRoleSetIsRejected() {
            User admin = adminUser();
            User client = registerClient("anna@example.com");
            assertThatThrownBy(() -> changeRoles.changeRoles(admin.id(), Set.of("ADMIN"), client.id(), Set.of()))
                    .satisfies(e -> assertCode(e, AuthErrors.ROLES_REQUIRED));
        }
    }

    private User adminUser() {
        EnsureInitialAdminUseCase ensure = new EnsureInitialAdminUseCase(users, new FakePasswordHasher(), clock);
        assertThat(ensure.ensure("admin@fitclub.local", "Admin-pass-1", "Админ"))
                .isEqualTo(EnsureInitialAdminUseCase.Result.CREATED);
        assertThat(ensure.ensure("admin@fitclub.local", "Admin-pass-1", "Админ"))
                .isEqualTo(EnsureInitialAdminUseCase.Result.ALREADY_PRESENT);
        return users.users.values().stream().filter(u -> u.hasRole(Role.ADMIN)).findFirst().orElseThrow();
    }
}
