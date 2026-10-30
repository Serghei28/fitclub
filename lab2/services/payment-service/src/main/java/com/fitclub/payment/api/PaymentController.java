package com.fitclub.payment.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
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
import com.fitclub.payment.application.CreatePaymentUseCase;
import com.fitclub.payment.application.PaymentQueryService;
import com.fitclub.payment.application.PaymentView;
import com.fitclub.payment.domain.PaymentStatus;

@RestController
@Tag(name = "Платежи (симулятор)", description = "Тестовая оплата: реальные деньги не списываются, данные карты "
        + "не запрашиваются")
class PaymentController {

    private final CreatePaymentUseCase createPayment;
    private final PaymentQueryService queries;

    PaymentController(CreatePaymentUseCase createPayment, PaymentQueryService queries) {
        this.createPayment = createPayment;
        this.queries = queries;
    }

    @Schema(description = "Только заказ: сумма и валюта берутся из membership-service на сервере")
    record CreatePaymentRequest(@NotNull UUID membershipOrderId) {
    }

    @Schema(description = "Платёж. simulated = true: это тестовый симулятор оплаты")
    record PaymentResponse(UUID id, UUID membershipOrderId, long amountMinor, String currency, PaymentStatus status,
            String provider, boolean simulated, String declineReason, Instant createdAt, Instant completedAt) {

        static PaymentResponse from(PaymentView v) {
            return new PaymentResponse(v.id(), v.membershipOrderId(), v.amountMinor(), v.currency(), v.status(),
                    v.provider(), "SIMULATOR".equals(v.provider()), v.declineReason(), v.createdAt(), v.completedAt());
        }
    }

    @PostMapping("/api/payments")
    @Operation(summary = "Создание платежа по своему заказу", description = "201 — новый платёж (PENDING); 200 — по "
            + "заказу уже есть платёж. Повтор с тем же Idempotency-Key возвращает исходный ответ.")
    ResponseEntity<PaymentResponse> create(
            @Parameter(description = "UUID, новый для каждого нового намерения") @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody CreatePaymentRequest request, AuthenticatedUser user) {
        return respond(createPayment.create(user.id(), key, new CreatePaymentUseCase.Request(request.membershipOrderId())));
    }

    @GetMapping("/api/payments/{id}")
    @Operation(summary = "Состояние своего платежа", description = "Чужой платёж — 404.")
    PaymentResponse get(@PathVariable UUID id, AuthenticatedUser user) {
        return PaymentResponse.from(queries.payment(id, user));
    }

    static ResponseEntity<PaymentResponse> respond(Outcome<PaymentView> outcome) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(outcome.status());
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(PaymentResponse.from(outcome.body()));
    }
}
