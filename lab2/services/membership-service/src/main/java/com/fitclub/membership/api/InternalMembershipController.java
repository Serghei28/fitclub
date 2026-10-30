package com.fitclub.membership.api;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fitclub.membership.api.dto.MembershipDtos.EligibilityResponse;
import com.fitclub.membership.api.dto.MembershipDtos.PaymentDetailsResponse;
import com.fitclub.membership.application.MembershipQueryService;

/**
 * Внутренний API. Каждый маршрут доступен только своему вызывающему (fitclub.internal.clients):
 * payment-service — реквизиты заказа, booking-service — право на посещение. Gateway их не публикует.
 */
@RestController
@Tag(name = "Внутренний API", description = "Только для сервисов (X-Service-Token), не для браузера")
@SecurityRequirement(name = "serviceToken")
class InternalMembershipController {

    private final MembershipQueryService queries;

    InternalMembershipController(MembershipQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/internal/membership-orders/{id}/payment-details")
    @Operation(summary = "Реквизиты заказа для оплаты (payment-service)", description = "Владелец, статус, сумма и "
            + "валюта из снимка заказа. 404 для неизвестного заказа.")
    PaymentDetailsResponse paymentDetails(@PathVariable UUID id) {
        return PaymentDetailsResponse.from(queries.paymentDetails(id));
    }

    @GetMapping("/internal/memberships/eligibility")
    @Operation(summary = "Право на посещение (booking-service)", description = "Абонемент, действующий в момент at "
            + "(starts_at <= at < ends_at): membership_id, период и visit_limit. Учёт посещений ведёт Booking.")
    EligibilityResponse eligibility(@RequestParam("user_id") UUID userId,
            @RequestParam("at") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        return queries.eligibility(userId, at)
                .map(m -> EligibilityResponse.eligible(userId, at, m))
                .orElseGet(() -> EligibilityResponse.notEligible(userId, at));
    }
}
