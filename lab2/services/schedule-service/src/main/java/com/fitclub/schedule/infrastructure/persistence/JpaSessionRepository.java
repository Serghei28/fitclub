package com.fitclub.schedule.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import com.fitclub.common.web.PageResponse;
import com.fitclub.schedule.application.port.SessionRepository;
import com.fitclub.schedule.domain.ScheduleErrors;
import com.fitclub.schedule.domain.Session;

/** Адаптер порта SessionRepository: JPA-сущность ↔ доменная модель, фильтры через Specification. */
@Repository
class JpaSessionRepository implements SessionRepository {

    private final SessionJpaRepository jpa;

    JpaSessionRepository(SessionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Session> findById(UUID id) {
        return jpa.findById(id).map(JpaSessionRepository::toDomain);
    }

    @Override
    public void add(Session session) {
        SessionEntity entity = new SessionEntity();
        entity.id = session.id();
        entity.createdAt = session.createdAt();
        copyState(session, entity);
        jpa.saveAndFlush(entity);
    }

    /**
     * Доменная модель уже увеличила version; в БД должна лежать предыдущая. Hibernate при flush
     * увеличит версию строки на 1, и она совпадёт с доменной — это aggregate_version события.
     */
    @Override
    public void update(Session session) {
        SessionEntity entity = jpa.findById(session.id()).orElseThrow(ScheduleErrors::sessionNotFound);
        if (entity.version != session.version() - 1) {
            throw new OptimisticLockingFailureException("Session " + session.id() + " was modified concurrently");
        }
        copyState(session, entity);
        jpa.flush();
    }

    @Override
    public PageResponse<Session> search(Criteria criteria, int page, int size) {
        Specification<SessionEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("status").in(criteria.statuses()));
            if (criteria.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startsAt"), criteria.from()));
            }
            if (criteria.to() != null) {
                predicates.add(cb.lessThan(root.get("startsAt"), criteria.to()));
            }
            if (criteria.trainerId() != null) {
                predicates.add(cb.equal(root.get("trainerId"), criteria.trainerId()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<SessionEntity> result = jpa.findAll(spec, PageRequest.of(page, size, Sort.by("startsAt", "id")));
        return PageResponse.of(result.getContent().stream().map(JpaSessionRepository::toDomain).toList(), page, size,
                result.getTotalElements());
    }

    private static void copyState(Session s, SessionEntity e) {
        e.trainingTypeId = s.trainingTypeId();
        e.trainerId = s.trainerId();
        e.trainerDisplayName = s.trainerDisplayName();
        e.title = s.title();
        e.startsAt = s.startsAt();
        e.endsAt = s.endsAt();
        e.capacity = s.capacity();
        e.status = s.status();
        e.publishedAt = s.publishedAt();
        e.cancelledAt = s.cancelledAt();
        e.cancelReason = s.cancelReason();
        e.updatedAt = s.updatedAt();
    }

    private static Session toDomain(SessionEntity e) {
        return Session.restore(e.id, e.trainingTypeId, e.trainerId, e.trainerDisplayName, e.title, e.startsAt,
                e.endsAt, e.capacity, e.status, e.publishedAt, e.cancelledAt, e.cancelReason, e.createdAt,
                e.updatedAt, e.version);
    }
}
