package com.fitclub.schedule.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fitclub.common.web.PageResponse;
import com.fitclub.schedule.domain.Session;
import com.fitclub.schedule.domain.SessionStatus;

/** Порт хранения занятий. */
public interface SessionRepository {

    Optional<Session> findById(UUID id);

    void add(Session session);

    /** Сохраняет изменения; при параллельном изменении бросает OptimisticLockingFailureException. */
    void update(Session session);

    PageResponse<Session> search(Criteria criteria, int page, int size);

    /**
     * @param from      начало занятия не раньше (включительно) или null
     * @param to        начало занятия раньше (исключительно) или null
     * @param trainerId фильтр по тренеру или null
     * @param statuses  допустимые статусы (не пустой)
     */
    record Criteria(Instant from, Instant to, UUID trainerId, Set<SessionStatus> statuses) {
    }
}
