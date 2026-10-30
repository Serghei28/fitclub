package com.fitclub.booking.api;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fitclub.booking.application.BookingQueryService;
import com.fitclub.booking.application.BookingView;
import com.fitclub.booking.application.CancelBookingUseCase;
import com.fitclub.booking.application.CreateBookingUseCase;
import com.fitclub.booking.domain.Booking;
import com.fitclub.booking.domain.BookingPolicy;
import com.fitclub.booking.domain.MembershipUsage;
import com.fitclub.booking.domain.SessionInventory;
import com.fitclub.common.idempotency.IdempotencyStore.Outcome;
import com.fitclub.common.security.AuthenticatedUser;
import com.fitclub.common.web.PageResponse;

/** HTTP-слой booking-service: DTO и вызов use case'ов. user_id — только из проверенного JWT. */
@RestController
@Tag(name = "Запись на занятия")
class BookingController {

    private final CreateBookingUseCase createBooking;
    private final CancelBookingUseCase cancelBooking;
    private final BookingQueryService queries;
    private final BookingPolicy policy;
    private final Clock clock;

    BookingController(CreateBookingUseCase createBooking, CancelBookingUseCase cancelBooking,
            BookingQueryService queries, BookingPolicy policy, Clock clock) {
        this.createBooking = createBooking;
        this.cancelBooking = cancelBooking;
        this.queries = queries;
        this.policy = policy;
        this.clock = clock;
    }

    record CreateBookingRequest(@NotNull UUID sessionId) {
    }

    @Schema(description = "Запись. cancellable — можно ли отменить сейчас; past — занятие уже началось (история)")
    record BookingResponse(UUID id, UUID sessionId, String sessionTitle, String trainerDisplayName, Instant startsAt,
            Instant endsAt, Booking.Status status, Booking.CancelReason cancelReason, Instant createdAt,
            Instant cancelledAt, boolean cancellable, Instant cancellableUntil, boolean past) {
    }

    record AvailabilityResponse(UUID sessionId, String state, Integer capacity, Integer booked, Integer available,
            Instant startsAt) {
    }

    @Schema(description = "Учёт посещений абонемента. visit_limit = null — безлимит")
    record UsageResponse(UUID membershipId, @JsonInclude(JsonInclude.Include.ALWAYS) Integer visitLimit,
            boolean unlimited, int used, @JsonInclude(JsonInclude.Include.ALWAYS) Integer remaining) {
    }

    record ParticipantResponse(UUID bookingId, UUID userId, String displayName, Instant bookedAt) {
    }

    record ParticipantsResponse(UUID sessionId, String sessionTitle, Integer capacity, int booked,
            List<ParticipantResponse> participants) {
    }

    @PostMapping("/api/bookings")
    @Operation(summary = "Запись на занятие", description = "Нужен действующий на дату занятия абонемент, свободное место "
            + "и неисчерпанный лимит. Повтор с тем же Idempotency-Key возвращает исходный ответ, даже если запись уже "
            + "отменена; для новой записи нужен новый ключ.")
    ResponseEntity<BookingResponse> create(
            @Parameter(description = "UUID, новый для каждого нового намерения") @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody CreateBookingRequest request, AuthenticatedUser user) {
        Outcome<BookingView> outcome = createBooking.book(
                new CreateBookingUseCase.Booker(user.id(), user.displayName()), key,
                new CreateBookingUseCase.Request(request.sessionId()));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(outcome.status());
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(toResponse(outcome.body()));
    }

    @PostMapping("/api/bookings/{id}/cancel")
    @Operation(summary = "Отмена своей записи", description = "Не позже чем за CANCEL_BEFORE_MINUTES (по умолчанию 120) "
            + "до начала. Повторная отмена возвращает текущее состояние и не возвращает посещение второй раз.")
    BookingResponse cancel(@PathVariable UUID id, AuthenticatedUser user) {
        return toResponse(cancelBooking.cancelByUser(user.id(), id));
    }

    @GetMapping("/api/bookings/me")
    @Operation(summary = "Мои записи и история")
    PageResponse<BookingResponse> mine(@RequestParam(required = false) Booking.Status status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, AuthenticatedUser user) {
        return queries.myBookings(user.id(), status, page, size).map(this::toResponse);
    }

    @GetMapping("/api/bookings/usage/me")
    @Operation(summary = "Использованные и оставшиеся посещения", description = "Учитываются будущие и прошедшие "
            + "подтверждённые записи. Абонемент без записей здесь не показан: использовано 0.")
    List<UsageResponse> usage(AuthenticatedUser user) {
        return queries.myUsage(user.id()).stream().map(BookingController::toUsage).toList();
    }

    @GetMapping("/api/availability")
    @Operation(summary = "Свободные места", description = "state: OPEN, CANCELLED или NOT_READY (занятие ещё не "
            + "дошло до Booking). Источник истины по местам — booking-service.")
    List<AvailabilityResponse> availability(
            @RequestParam("session_id") @Size(min = 1, max = 100) List<UUID> sessionIds) {
        var known = queries.availability(sessionIds);
        return sessionIds.stream().distinct().map(id -> known.stream().filter(s -> s.sessionId().equals(id)).findFirst()
                .map(BookingController::toAvailability)
                .orElse(new AvailabilityResponse(id, "NOT_READY", null, null, null, null))).toList();
    }

    @GetMapping("/api/sessions/{id}/bookings")
    @Operation(summary = "Участники занятия", description = "Только назначенный тренер этого занятия или ADMIN.")
    ParticipantsResponse participants(@PathVariable UUID id, AuthenticatedUser user) {
        BookingQueryService.Participants p = queries.participants(id, user);
        return new ParticipantsResponse(p.session().sessionId(), p.session().title(), p.session().capacity(),
                p.session().bookedCount(), p.bookings().stream().map(b -> new ParticipantResponse(b.id(), b.userId(),
                        b.userDisplayName(), b.createdAt())).toList());
    }

    private BookingResponse toResponse(BookingView v) {
        Instant now = clock.instant();
        boolean past = v.startsAt() != null && !now.isBefore(v.startsAt());
        Instant until = v.startsAt() == null ? null : policy.cancellationDeadline(v.startsAt());
        boolean cancellable = v.status() == Booking.Status.CONFIRMED && v.startsAt() != null
                && policy.userMayCancel(v.startsAt(), now);
        return new BookingResponse(v.id(), v.sessionId(), v.sessionTitle(), v.trainerDisplayName(), v.startsAt(),
                v.endsAt(), v.status(), v.cancelReason(), v.createdAt(), v.cancelledAt(), cancellable, until, past);
    }

    private static UsageResponse toUsage(MembershipUsage u) {
        return new UsageResponse(u.membershipId(), u.visitLimit(), u.unlimited(), u.usedCount(), u.remaining());
    }

    private static AvailabilityResponse toAvailability(SessionInventory s) {
        return new AvailabilityResponse(s.sessionId(), s.status().name(), s.capacity(), s.bookedCount(),
                s.isOpen() ? s.available() : 0, s.startsAt());
    }
}
