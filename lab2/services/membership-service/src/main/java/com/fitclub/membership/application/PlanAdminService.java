package com.fitclub.membership.application;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitclub.membership.application.port.MembershipPorts.PlanRepository;
import com.fitclub.membership.domain.MembershipErrors;
import com.fitclub.membership.domain.Money;
import com.fitclub.membership.domain.Plan;

/** Управление тарифами (ADMIN). Изменение тарифа не меняет уже созданные заказы. */
@Service
public class PlanAdminService {

    private static final Logger log = LoggerFactory.getLogger(PlanAdminService.class);

    private final PlanRepository plans;
    private final Clock clock;

    public PlanAdminService(PlanRepository plans, Clock clock) {
        this.plans = plans;
        this.clock = clock;
    }

    public record CreatePlan(String name, int durationDays, Integer visitLimit, long priceMinor, String currency) {
    }

    @Transactional
    public Plan create(CreatePlan command) {
        Plan plan = Plan.create(UUID.randomUUID(), command.name(), command.durationDays(), command.visitLimit(),
                new Money(command.priceMinor(), command.currency()), clock.instant());
        plans.add(plan);
        log.info("Plan created: plan_id={}", plan.id());
        return plan;
    }

    @Transactional
    public Plan change(UUID planId, Plan.Changes changes) {
        Plan plan = plans.findById(planId).orElseThrow(() -> MembershipErrors.notFound("Тариф"));
        plan.change(changes, clock.instant());
        plans.update(plan);
        log.info("Plan changed: plan_id={}, active={}", planId, plan.active());
        return plan;
    }
}
