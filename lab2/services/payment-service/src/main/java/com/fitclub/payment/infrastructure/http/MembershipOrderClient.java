package com.fitclub.payment.infrastructure.http;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.fitclub.common.http.InternalServiceClient;
import com.fitclub.payment.application.port.PaymentPorts.MembershipOrderReader;

/**
 * HTTP-адаптер: GET /internal/membership-orders/{id}/payment-details в membership-service
 * с X-Service-Token направления payment → membership. Таймауты и политика ошибок — InternalServiceClient.
 */
public class MembershipOrderClient implements MembershipOrderReader {

    private final InternalServiceClient membership;

    public MembershipOrderClient(InternalServiceClient membership) {
        this.membership = membership;
    }

    @Override
    public Optional<OrderDetails> paymentDetails(UUID orderId) {
        return membership.getOptional("/internal/membership-orders/{id}/payment-details", PaymentDetailsResponse.class,
                body -> new OrderDetails(Objects.requireNonNull(body.orderId()), Objects.requireNonNull(body.userId()),
                        Objects.requireNonNull(body.status()), Objects.requireNonNull(body.amountMinor()),
                        Objects.requireNonNull(body.currency())),
                orderId);
    }

    /** Контракт ответа membership-service (docs/api.md). */
    record PaymentDetailsResponse(
            @JsonProperty("order_id") UUID orderId,
            @JsonProperty("user_id") UUID userId,
            @JsonProperty("status") String status,
            @JsonProperty("amount_minor") Long amountMinor,
            @JsonProperty("currency") String currency) {
    }
}
