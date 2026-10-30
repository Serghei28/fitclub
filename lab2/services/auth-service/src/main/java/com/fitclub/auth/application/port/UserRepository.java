package com.fitclub.auth.application.port;

import java.util.Optional;
import java.util.UUID;

import com.fitclub.auth.domain.Email;
import com.fitclub.auth.domain.Role;
import com.fitclub.auth.domain.User;
import com.fitclub.common.web.PageResponse;

/** Порт хранения пользователей. Реализация — JPA-адаптер в infrastructure.persistence. */
public interface UserRepository {

    Optional<User> findById(UUID id);

    Optional<User> findByEmail(Email email);

    boolean existsWithRole(Role role);

    /** Добавляет пользователя; при занятом email бросает AuthErrors.emailAlreadyUsed() (в том числе при гонке). */
    void add(User user);

    /** Сохраняет изменения; при параллельном изменении бросает OptimisticLockingFailureException. */
    void update(User user);

    /**
     * @param role  фильтр по роли или null
     * @param text  подстрока email или имени (без учёта регистра) или null
     */
    PageResponse<User> search(Role role, String text, int page, int size);
}
