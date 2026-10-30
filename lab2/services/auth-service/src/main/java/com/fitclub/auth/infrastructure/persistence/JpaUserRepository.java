package com.fitclub.auth.infrastructure.persistence;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import com.fitclub.auth.application.port.UserRepository;
import com.fitclub.auth.domain.AuthErrors;
import com.fitclub.auth.domain.Email;
import com.fitclub.auth.domain.Role;
import com.fitclub.auth.domain.User;
import com.fitclub.common.web.PageResponse;

/** Адаптер порта UserRepository: преобразует доменную модель в JPA-сущности и обратно. */
@Repository
class JpaUserRepository implements UserRepository {

    private final UserJpaRepository jpa;
    private final Clock clock;

    JpaUserRepository(UserJpaRepository jpa, Clock clock) {
        this.jpa = jpa;
        this.clock = clock;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpa.findById(id).map(JpaUserRepository::toDomain);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return jpa.findByEmail(email.value()).map(JpaUserRepository::toDomain);
    }

    @Override
    public boolean existsWithRole(Role role) {
        return jpa.existsWithRole(role.name());
    }

    @Override
    public void add(User user) {
        UserEntity entity = new UserEntity();
        entity.id = user.id();
        entity.createdAt = user.createdAt();
        copyState(user, entity);
        try {
            // flush сразу: нарушение uq_users_email проявится здесь, а не при коммите
            jpa.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_users_email")) {
                throw AuthErrors.emailAlreadyUsed();
            }
            throw e;
        }
    }

    @Override
    public void update(User user) {
        UserEntity entity = jpa.findById(user.id()).orElseThrow(AuthErrors::userNotFound);
        if (entity.version != user.version()) {
            throw new OptimisticLockingFailureException("User " + user.id() + " was modified concurrently");
        }
        copyState(user, entity);
        jpa.flush();
    }

    @Override
    public PageResponse<User> search(Role role, String text, int page, int size) {
        String pattern = text == null ? "%" : "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
        PageRequest pageable = PageRequest.of(page, size, Sort.by("email"));
        Page<UserEntity> result = role == null
                ? jpa.search(pattern, pageable)
                : jpa.searchByRole(role.name(), pattern, pageable);
        return PageResponse.of(result.getContent().stream().map(JpaUserRepository::toDomain).toList(),
                page, size, result.getTotalElements());
    }

    private void copyState(User user, UserEntity entity) {
        entity.email = user.email().value();
        entity.passwordHash = user.passwordHash();
        entity.displayName = user.displayName();
        entity.active = user.isActive();
        entity.updatedAt = user.updatedAt();
        Set<String> wanted = user.roles().stream().map(Role::name).collect(Collectors.toSet());
        entity.roles.removeIf(existing -> !wanted.contains(existing.roleCode));
        Set<String> present = entity.roles.stream().map(r -> r.roleCode).collect(Collectors.toSet());
        wanted.stream()
                .filter(code -> !present.contains(code))
                .forEach(code -> entity.roles.add(new UserRoleEmbeddable(code, clock.instant())));
    }

    private static User toDomain(UserEntity entity) {
        Set<Role> roles = entity.roles.isEmpty()
                ? EnumSet.noneOf(Role.class)
                : entity.roles.stream().map(r -> Role.valueOf(r.roleCode))
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
        return User.restore(entity.id, new Email(entity.email), entity.passwordHash, entity.displayName,
                entity.active, roles, entity.createdAt, entity.updatedAt, entity.version);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
