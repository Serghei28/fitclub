package com.fitclub.payment.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fitclub.common.security.AuthenticatedUser;
import com.fitclub.payment.api.PaymentController.PaymentResponse;
import com.fitclub.payment.application.CompletePaymentUseCase;
import com.fitclub.payment.application.port.PaymentProvider;

/**
 * DEMO-завершение тестового платежа. Бин существует только при fitclub.payment.simulation-endpoint=true
 * (профили local, demo, test); в профиле docker без demo маршрута нет (404). Доступно только владельцу платежа.
 */
@RestController
@ConditionalOnProperty(prefix = "fitclub.payment", name = "simulation-endpoint", havingValue = "true")
@Tag(name = "Платежи (симулятор)")
class PaymentSimulationController {

    private final CompletePaymentUseCase completePayment;

    PaymentSimulationController(CompletePaymentUseCase completePayment) {
        this.completePayment = completePayment;
    }

    record SimulateRequest(@NotNull PaymentProvider.Instruction outcome) {
    }

    @PostMapping("/api/payments/{id}/simulate")
    @Operation(summary = "DEMO: тестовое завершение оплаты", description = "outcome: SUCCESS или DECLINE. Реальные "
            + "деньги не списываются. Повтор с тем же Idempotency-Key не создаёт второго события.")
    ResponseEntity<PaymentResponse> simulate(@PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody SimulateRequest request, AuthenticatedUser user) {
        return PaymentController.respond(completePayment.complete(user.id(), key,
                new CompletePaymentUseCase.Request(id, request.outcome())));
    }
}
