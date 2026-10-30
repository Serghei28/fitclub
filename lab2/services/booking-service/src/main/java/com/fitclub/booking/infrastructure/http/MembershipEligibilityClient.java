package com.fitclub.booking.infrastructure.http;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.fitclub.booking.application.port.BookingPorts.MembershipEligibilityReader;
import com.fitclub.common.error.ApiException;
import com.fitclub.common.http.InternalServiceClient;

/**
 * HTTP-адаптер: GET /internal/memberships/eligibility в membership-service (X-Service-Token booking → membership).
 * «Права нет» — пустой Optional. Сбой Membership — ApiException 502/503/504: запись не разрешается
 * по придуманному успешному результату.
 */
public class MembershipEligibilityClient implements MembershipEligibilityReader {

    private final InternalServiceClient membership;

    public MembershipEligibilityClient(InternalServiceClient membership) {
        this.membership = membership;
    }

    @Override
    public Optional<Eligibility> eligibility(UUID userId, Instant at) {
        Optional<Optional<Eligibility>> answer = membership.getOptional(
                "/internal/memberships/eligibility?user_id={user}&at={at}", EligibilityResponse.class,
                MembershipEligibilityClient::toEligibility, userId, at.toString());
        // 404 у этого маршрута не предусмотрен: отсутствие ответа — некорректный ответ зависимости
        return answer.orElseThrow(ApiException::dependencyBadResponse);
    }

    private static Optional<Eligibility> toEligibility(EligibilityResponse body) {
        if (!Objects.requireNonNull(body.eligible())) {
            return Optional.empty();
        }
        return Optional.of(new Eligibility(Objects.requireNonNull(body.membershipId()),
                Objects.requireNonNull(body.startsAt()), Objects.requireNonNull(body.endsAt()), body.visitLimit()));
    }

    /** Контракт ответа membership-service (docs/api.md). */
    record EligibilityResponse(
            @JsonProperty("eligible") Boolean eligible,
            @JsonProperty("membership_id") UUID membershipId,
            @JsonProperty("starts_at") Instant startsAt,
            @JsonProperty("ends_at") Instant endsAt,
            @JsonProperty("visit_limit") Integer visitLimit) {
    }
}
