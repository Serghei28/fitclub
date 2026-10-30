package com.fitclub.membership.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fitclub.common.security.AuthenticatedUser;
import com.fitclub.membership.api.dto.MembershipDtos.CreatePlanRequest;
import com.fitclub.membership.api.dto.MembershipDtos.PlanResponse;
import com.fitclub.membership.api.dto.MembershipDtos.UpdatePlanRequest;
import com.fitclub.membership.application.MembershipQueryService;
import com.fitclub.membership.application.PlanAdminService;
import com.fitclub.membership.domain.Plan;

@RestController
@RequestMapping("/api/plans")
@Tag(name = "Тарифы")
class PlanController {

    private final MembershipQueryService queries;
    private final PlanAdminService admin;

    PlanController(MembershipQueryService queries, PlanAdminService admin) {
        this.queries = queries;
        this.admin = admin;
    }

    @GetMapping
    @Operation(summary = "Тарифы", description = "Активные тарифы; ADMIN может запросить и снятые с продажи.")
    List<PlanResponse> list(@RequestParam(name = "include_inactive", defaultValue = "false") boolean includeInactive,
            AuthenticatedUser user) {
        return queries.plans(user, includeInactive).stream().map(PlanResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Создание тарифа (ADMIN)")
    PlanResponse create(@Valid @RequestBody CreatePlanRequest r) {
        return PlanResponse.from(admin.create(new PlanAdminService.CreatePlan(r.name(), r.durationDays(),
                r.visitLimit(), r.priceMinor(), r.currency() == null ? "MDL" : r.currency())));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Изменение или деактивация тарифа (ADMIN)", description = "Уже созданные заказы не меняются: "
            + "они хранят снимок тарифа.")
    PlanResponse update(@PathVariable UUID id, @Valid @RequestBody UpdatePlanRequest r) {
        return PlanResponse.from(admin.change(id, new Plan.Changes(r.name(), r.durationDays(), r.visitLimit(),
                r.unlimited(), r.priceMinor(), r.currency(), r.active())));
    }
}
