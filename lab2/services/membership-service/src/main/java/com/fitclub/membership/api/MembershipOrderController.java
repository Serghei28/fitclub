package com.fitclub.membership.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fitclub.common.idempotency.IdempotencyStore.Outcome;
import com.fitclub.common.security.AuthenticatedUser;
import com.fitclub.membership.api.dto.MembershipDtos.CreateOrderRequest;
import com.fitclub.membership.api.dto.MembershipDtos.MembershipResponse;
import com.fitclub.membership.api.dto.MembershipDtos.OrderResponse;
import com.fitclub.membership.application.MembershipQueryService;
import com.fitclub.membership.application.OrderView;
import com.fitclub.membership.application.PlaceOrderUseCase;

/** Заказы и абонементы текущего пользователя. user_id — только из проверенного JWT. */
@RestController
@Tag(name = "Заказы и абонементы")
class MembershipOrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PlaceOrderUseCase placeOrder;
    private final MembershipQueryService queries;

    MembershipOrderController(PlaceOrderUseCase placeOrder, MembershipQueryService queries) {
        this.placeOrder = placeOrder;
        this.queries = queries;
    }

    @PostMapping("/api/membership-orders")
    @Operation(summary = "Заказ абонемента", description = "201 — новый заказ; 200 — уже ожидающий оплаты заказ того же "
            + "тарифа. Повтор с тем же Idempotency-Key возвращает исходный ответ (заголовок Idempotent-Replayed: true).")
    ResponseEntity<OrderResponse> create(
            @Parameter(description = "UUID, новый для каждого нового намерения") @RequestHeader(IDEMPOTENCY_KEY) UUID key,
            @Valid @RequestBody CreateOrderRequest request, AuthenticatedUser user) {
        Outcome<OrderView> outcome = placeOrder.place(user.id(), key, new PlaceOrderUseCase.Request(request.planId()));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(outcome.status());
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(OrderResponse.from(outcome.body()));
    }

    @GetMapping("/api/membership-orders/{id}")
    @Operation(summary = "Статус своего заказа", description = "Чужой заказ — 404.")
    OrderResponse get(@PathVariable UUID id, AuthenticatedUser user) {
        return OrderResponse.from(queries.order(id, user));
    }

    @GetMapping("/api/memberships/me")
    @Operation(summary = "Мои абонементы", description = "status (UPCOMING/ACTIVE/EXPIRED) считается по времени.")
    List<MembershipResponse> myMemberships(AuthenticatedUser user) {
        return queries.myMemberships(user.id()).stream().map(MembershipResponse::from).toList();
    }
}
