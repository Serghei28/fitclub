package com.fitclub.membership.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fitclub.membership.application.port.MembershipPorts.MembershipRepository;
import com.fitclub.membership.application.port.MembershipPorts.OrderRepository;
import com.fitclub.membership.application.port.MembershipPorts.PlanRepository;
import com.fitclub.membership.domain.Membership;
import com.fitclub.membership.domain.MembershipErrors;
import com.fitclub.membership.domain.MembershipOrder;
import com.fitclub.membership.domain.Money;
import com.fitclub.membership.domain.OrderStatus;
import com.fitclub.membership.domain.Plan;
import com.fitclub.membership.domain.PlanSnapshot;
import com.fitclub.membership.infrastructure.persistence.MembershipEntities.MembershipEntity;
import com.fitclub.membership.infrastructure.persistence.MembershipEntities.OrderEntity;
import com.fitclub.membership.infrastructure.persistence.MembershipEntities.PlanEntity;

/** JPA-адаптеры портов membership-service: перевод сущностей хранения в доменные объекты и обратно. */
final class JpaMembershipAdapters {

    private JpaMembershipAdapters() {
    }

    @Repository
    static class JpaPlanRepository implements PlanRepository {

        private final PlanJpaRepository jpa;

        JpaPlanRepository(PlanJpaRepository jpa) {
            this.jpa = jpa;
        }

        @Override
        public Optional<Plan> findById(UUID id) {
            return jpa.findById(id).map(JpaPlanRepository::toDomain);
        }

        @Override
        public List<Plan> findAll(boolean includeInactive) {
            return (includeInactive ? jpa.findAllByOrderByPriceMinorAscNameAsc()
                    : jpa.findByActiveTrueOrderByPriceMinorAscNameAsc()).stream().map(JpaPlanRepository::toDomain)
                    .toList();
        }

        @Override
        public void add(Plan plan) {
            PlanEntity entity = new PlanEntity();
            entity.id = plan.id();
            entity.createdAt = plan.createdAt();
            copy(plan, entity);
            save(entity);
        }

        @Override
        public void update(Plan plan) {
            PlanEntity entity = jpa.findById(plan.id()).orElseThrow(() -> MembershipErrors.notFound("Тариф"));
            if (entity.version != plan.version()) {
                throw new OptimisticLockingFailureException("Plan " + plan.id() + " was modified concurrently");
            }
            copy(plan, entity);
            save(entity);
        }

        private void save(PlanEntity entity) {
            try {
                jpa.saveAndFlush(entity);
            } catch (DataIntegrityViolationException e) {
                if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_plans_name")) {
                    throw MembershipErrors.planNameTaken();
                }
                throw e;
            }
        }

        private static void copy(Plan p, PlanEntity e) {
            e.name = p.name();
            e.durationDays = p.durationDays();
            e.visitLimit = p.visitLimit();
            e.priceMinor = p.price().amountMinor();
            e.currency = p.price().currency();
            e.active = p.active();
            e.updatedAt = p.updatedAt();
        }

        private static Plan toDomain(PlanEntity e) {
            return Plan.restore(e.id, e.name, e.durationDays, e.visitLimit, new Money(e.priceMinor, e.currency),
                    e.active, e.createdAt, e.updatedAt, e.version);
        }
    }

    @Repository
    static class JpaOrderRepository implements OrderRepository {

        private final OrderJpaRepository jpa;
        private final JdbcTemplate jdbc;

        JpaOrderRepository(OrderJpaRepository jpa, JdbcTemplate jdbc) {
            this.jpa = jpa;
            this.jdbc = jdbc;
        }

        @Override
        public Optional<MembershipOrder> findById(UUID id) {
            return jpa.findById(id).map(JpaOrderRepository::toDomain);
        }

        @Override
        public Optional<MembershipOrder> findPendingByUser(UUID userId) {
            return jpa.findFirstByUserIdAndStatus(userId, OrderStatus.AWAITING_PAYMENT).map(JpaOrderRepository::toDomain);
        }

        @Override
        public void lockUser(UUID userId) {
            jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) l",
                    Integer.class, "membership-orders:" + userId);
        }

        @Override
        public void add(MembershipOrder order) {
            OrderEntity e = new OrderEntity();
            e.id = order.id();
            e.userId = order.userId();
            PlanSnapshot plan = order.plan();
            e.planId = plan.planId();
            e.planName = plan.planName();
            e.durationDays = plan.durationDays();
            e.visitLimit = plan.visitLimit();
            e.priceMinor = plan.price().amountMinor();
            e.currency = plan.price().currency();
            e.status = order.status();
            e.paymentId = order.paymentId();
            e.paidAt = order.paidAt();
            e.failedAt = order.failedAt();
            e.createdAt = order.createdAt();
            e.updatedAt = order.updatedAt();
            try {
                jpa.saveAndFlush(e);
            } catch (DataIntegrityViolationException ex) {
                if (String.valueOf(ex.getMostSpecificCause().getMessage()).contains("uq_orders_one_pending_per_user")) {
                    throw MembershipErrors.pendingOrderExists();
                }
                throw ex;
            }
        }

        @Override
        public void update(MembershipOrder order) {
            OrderEntity e = jpa.findById(order.id()).orElseThrow(() -> MembershipErrors.notFound("Заказ"));
            if (e.version != order.version()) {
                throw new OptimisticLockingFailureException("Order " + order.id() + " was modified concurrently");
            }
            e.status = order.status();
            e.paymentId = order.paymentId();
            e.paidAt = order.paidAt();
            e.failedAt = order.failedAt();
            e.updatedAt = order.updatedAt();
            jpa.flush();
        }

        static MembershipOrder toDomain(OrderEntity e) {
            return MembershipOrder.restore(e.id, e.userId,
                    new PlanSnapshot(e.planId, e.planName, e.durationDays, e.visitLimit,
                            new Money(e.priceMinor, e.currency)),
                    e.status, e.paymentId, e.paidAt, e.failedAt, e.createdAt, e.updatedAt, e.version);
        }
    }

    @Repository
    static class JpaMembershipRepository implements MembershipRepository {

        private final MembershipJpaRepository jpa;
        private final JdbcTemplate jdbc;

        JpaMembershipRepository(MembershipJpaRepository jpa, JdbcTemplate jdbc) {
            this.jpa = jpa;
            this.jdbc = jdbc;
        }

        /** INSERT … ON CONFLICT DO NOTHING: второй абонемент по тому же заказу/платежу не создаётся и не ломает транзакцию. */
        @Override
        public boolean addIfAbsent(Membership m) {
            int inserted = jdbc.update("""
                    insert into memberships (id, user_id, order_id, payment_id, plan_name, visit_limit, starts_at,
                        ends_at, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict do nothing
                    """, m.id(), m.userId(), m.orderId(), m.paymentId(), m.planName(), m.visitLimit(),
                    Timestamp.from(m.startsAt()), Timestamp.from(m.endsAt()), Timestamp.from(m.createdAt()));
            return inserted == 1;
        }

        @Override
        public List<Membership> findByUser(UUID userId) {
            return jpa.findByUserId(userId).stream().map(JpaMembershipRepository::toDomain).toList();
        }

        @Override
        public Optional<Membership> findValidAt(UUID userId, Instant moment) {
            return jpa.findValidAt(userId, moment).stream().findFirst().map(JpaMembershipRepository::toDomain);
        }

        @Override
        public boolean existsNotEndedAt(UUID userId, Instant moment) {
            return jpa.existsByUserIdAndEndsAtAfter(userId, moment);
        }

        static Membership toDomain(MembershipEntity e) {
            return new Membership(e.id, e.userId, e.orderId, e.paymentId, e.planName, e.visitLimit, e.startsAt,
                    e.endsAt, e.createdAt);
        }
    }
}
