package com.fitclub.auth.application;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.auth.application.port.UserRepository;
import com.fitclub.auth.domain.AuthErrors;
import com.fitclub.auth.domain.Role;
import com.fitclub.auth.domain.User;
import com.fitclub.common.error.ApiException;

/**
 * Назначение ролей администратором. Новые роли попадут в access-токен пользователя
 * при следующем входе или обновлении сессии.
 */
@Service
public class ChangeUserRolesUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChangeUserRolesUseCase.class);

    private final UserRepository users;
    private final Clock clock;

    public ChangeUserRolesUseCase(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /** @param actorId администратор из проверенного JWT */
    @Transactional
    public User changeRoles(UUID actorId, Set<String> actorRoles, UUID targetUserId, Set<Role> newRoles) {
        // Права проверяются и здесь, а не только аннотацией контроллера
        if (!actorRoles.contains(Role.ADMIN.name())) {
            throw ApiException.forbidden("Назначать роли может только администратор.");
        }
        User target = users.findById(targetUserId).orElseThrow(AuthErrors::userNotFound);
        if (target.id().equals(actorId) && !newRoles.contains(Role.ADMIN)) {
            throw AuthErrors.cannotRevokeOwnAdmin();
        }
        target.replaceRoles(newRoles, clock.instant());
        users.update(target);
        log.info("Roles changed: user_id={}, roles={}, by admin_id={}", target.id(), newRoles, actorId);
        return target;
    }
}
