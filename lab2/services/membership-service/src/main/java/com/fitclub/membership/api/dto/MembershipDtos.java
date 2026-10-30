package com.fitclub.membership.api.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fitclub.membership.application.MembershipQueryService.MembershipView;
import com.fitclub.membership.application.OrderView;
import com.fitclub.membership.domain.Membership;
import com.fitclub.membership.domain.MembershipOrder;
import com.fitclub.membership.domain.OrderStatus;
import com.fitclub.membership.domain.Plan;

/**
 * Request/response DTO membership-service. Деньги — целое число минимальных единиц (price_minor).
 * visit_limit в ответах присутствует всегда: null означает безлимит (по контракту задания).
 */
public final class MembershipDtos {

    private MembershipDtos() {
    }

    public record CreatePlanRequest(
            @NotBlank @Size(max = 100) @Schema(example = "8 посещений в месяц") String name,
            @NotNull @Min(1) @Max(3660) @Schema(example = "30") Integer durationDays,
            @Min(1) @Schema(description = "Не указывайте для безлимитного тарифа", example = "8") Integer visitLimit,
            @NotNull @Min(0) @Schema(description = "Цена в минимальных единицах: 45000 = 450.00 MDL", example = "45000")
            Long priceMinor,
            @Pattern(regexp = "^[A-Z]{3}$") @Schema(example = "MDL", defaultValue = "MDL") String currency) {
    }

    public record UpdatePlanRequest(
            @Size(min = 1, max = 100) String name,
            @Min(1) @Max(3660) Integer durationDays,
            @Min(1) Integer visitLimit,
            @Schema(description = "true — сделать тариф безлимитным") Boolean unlimited,
            @Min(0) Long priceMinor,
            @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @Schema(description = "false — снять тариф с продажи") Boolean active) {
    }

    public record PlanResponse(UUID id, String name, int durationDays,
            @JsonInclude(JsonInclude.Include.ALWAYS) Integer visitLimit, boolean unlimited,
            long priceMinor, String currency, boolean active) {

        public static PlanResponse from(Plan p) {
            return new PlanResponse(p.id(), p.name(), p.durationDays(), p.visitLimit(), p.unlimited(),
                    p.price().amountMinor(), p.price().currency(), p.active());
        }
    }

    @Schema(description = "Клиент выбирает только тариф: цена, срок и лимит берутся из тарифа на сервере")
    public record CreateOrderRequest(@NotNull UUID planId) {
    }

    public record OrderResponse(UUID id, UUID planId, String planName, int durationDays,
            @JsonInclude(JsonInclude.Include.ALWAYS) Integer visitLimit,
            boolean unlimited, long priceMinor, String currency, OrderStatus status, UUID paymentId, Instant paidAt,
            Instant failedAt, Instant createdAt) {

        public static OrderResponse from(OrderView v) {
            return new OrderResponse(v.id(), v.planId(), v.planName(), v.durationDays(), v.visitLimit(),
                    v.visitLimit() == null, v.priceMinor(), v.currency(), v.status(), v.paymentId(), v.paidAt(),
                    v.failedAt(), v.createdAt());
        }

        public static OrderResponse from(MembershipOrder order) {
            return from(OrderView.from(order));
        }
    }

    @Schema(description = "Абонемент. Действует на интервале [starts_at, ends_at). Использованные посещения — "
            + "в booking-service: GET /api/bookings/usage/me")
    public record MembershipResponse(UUID id, UUID orderId, String planName,
            @JsonInclude(JsonInclude.Include.ALWAYS) Integer visitLimit, boolean unlimited,
            Instant startsAt, Instant endsAt, Membership.Status status) {

        public static MembershipResponse from(MembershipView v) {
            Membership m = v.membership();
            return new MembershipResponse(m.id(), m.orderId(), m.planName(), m.visitLimit(), m.visitLimit() == null,
                    m.startsAt(), m.endsAt(), v.status());
        }
    }

    /** Внутренний ответ для payment-service: сумма и валюта — снимок заказа, владелец — для проверки. */
    public record PaymentDetailsResponse(UUID orderId, UUID userId, OrderStatus status, long amountMinor,
            String currency, String planName) {

        public static PaymentDetailsResponse from(MembershipOrder o) {
            return new PaymentDetailsResponse(o.id(), o.userId(), o.status(), o.plan().price().amountMinor(),
                    o.plan().price().currency(), o.plan().planName());
        }
    }

    /** Внутренний ответ для booking-service: право на посещение в момент at. */
    public record EligibilityResponse(UUID userId, Instant at, boolean eligible, String reason, UUID membershipId,
            Instant startsAt, Instant endsAt, @JsonInclude(JsonInclude.Include.ALWAYS) Integer visitLimit) {

        public static EligibilityResponse eligible(UUID userId, Instant at, Membership m) {
            return new EligibilityResponse(userId, at, true, null, m.id(), m.startsAt(), m.endsAt(), m.visitLimit());
        }

        public static EligibilityResponse notEligible(UUID userId, Instant at) {
            return new EligibilityResponse(userId, at, false, "NO_VALID_MEMBERSHIP", null, null, null, null);
        }
    }
}
