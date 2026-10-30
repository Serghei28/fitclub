package com.fitclub.membership.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fitclub.membership.domain.Membership;
import com.fitclub.membership.domain.MembershipOrder;
import com.fitclub.membership.domain.Plan;

/**
 * Порты хранения membership-service. Узкие интерфейсы по агрегатам: сценарий зависит только от того,
 * что ему нужно (ISP). Реализации — JPA-адаптеры в infrastructure.persistence.
 */
public final class MembershipPorts {

    private MembershipPorts() {
    }

    public interface PlanRepository {

        Optional<Plan> findById(UUID id);

        List<Plan> findAll(boolean includeInactive);

        /** При занятом названии бросает MembershipErrors.planNameTaken(). */
        void add(Plan plan);

        void update(Plan plan);
    }

    public interface OrderRepository {

        Optional<MembershipOrder> findById(UUID id);

        Optional<MembershipOrder> findPendingByUser(UUID userId);

        /**
         * Блокировка «заказы пользователя» до конца транзакции (pg_advisory_xact_lock):
         * параллельные заказы одного пользователя выполняются по очереди.
         */
        void lockUser(UUID userId);

        /** Нарушение «один ожидающий заказ на пользователя» → MembershipErrors.pendingOrderExists(). */
        void add(MembershipOrder order);
    }

    public interface MembershipRepository {

        List<Membership> findByUser(UUID userId);

        /** Абонемент, действующий в момент moment: starts_at <= moment < ends_at. */
        Optional<Membership> findValidAt(UUID userId, Instant moment);

        /** Есть ли у пользователя абонемент, который ещё не закончился (действует или начнётся позже). */
        boolean existsNotEndedAt(UUID userId, Instant moment);
    }
}
