package com.fitclub.auth.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.auth.application.port.UserRepository;
import com.fitclub.auth.domain.AuthErrors;
import com.fitclub.auth.domain.Role;
import com.fitclub.auth.domain.User;
import com.fitclub.common.web.PageResponse;

/** Чтение профилей: свой профиль, поиск для ADMIN и статус тренера для внутреннего API. */
@Service
@Transactional(readOnly = true)
public class UserQueryService {

    private final UserRepository users;

    public UserQueryService(UserRepository users) {
        this.users = users;
    }

    public User profile(UUID userId) {
        return users.findById(userId).orElseThrow(AuthErrors::userNotFound);
    }

    public PageResponse<User> search(Role role, String text, int page, int size) {
        String normalized = text == null || text.isBlank() ? null : text.strip();
        return users.search(role, normalized, page, Math.min(size, PageResponse.MAX_SIZE));
    }

    /** Для schedule-service: можно ли назначить пользователя тренером занятия. */
    public TrainerStatus trainerStatus(UUID userId) {
        User user = users.findById(userId).orElseThrow(AuthErrors::userNotFound);
        return new TrainerStatus(user.id(), user.isActive(), user.isActiveTrainer(), user.displayName());
    }

    public record TrainerStatus(UUID userId, boolean active, boolean trainer, String displayName) {
    }
}
