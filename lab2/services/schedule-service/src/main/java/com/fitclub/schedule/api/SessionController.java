package com.fitclub.schedule.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
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
import com.fitclub.common.web.PageResponse;
import com.fitclub.schedule.api.dto.ScheduleDtos.CancelSessionRequest;
import com.fitclub.schedule.api.dto.ScheduleDtos.CreateSessionRequest;
import com.fitclub.schedule.api.dto.ScheduleDtos.SessionResponse;
import com.fitclub.schedule.api.dto.ScheduleDtos.UpdateSessionRequest;
import com.fitclub.schedule.application.ScheduleCommandService;
import com.fitclub.schedule.application.ScheduleQueryService;
import com.fitclub.schedule.domain.SessionStatus;

/** HTTP-слой расписания: DTO, валидация, роли; правила — в домене и use case'ах. */
@RestController
@RequestMapping("/api/sessions")
@Tag(name = "Расписание", description = "Черновики видит только ADMIN; после публикации время, тренер и вместимость "
        + "не меняются")
class SessionController {

    private final ScheduleQueryService queries;
    private final ScheduleCommandService commands;

    SessionController(ScheduleQueryService queries, ScheduleCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @GetMapping
    @Operation(summary = "Расписание", description = "Фильтры: from/to (по началу занятия, ISO 8601), trainer_id, "
            + "status. Сортировка по началу.")
    PageResponse<SessionResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "trainer_id", required = false) UUID trainerId,
            @RequestParam(required = false) SessionStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            AuthenticatedUser user) {
        return queries.search(from, to, trainerId, status, user, page, size).map(SessionResponse::from);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('TRAINER')")
    @Operation(summary = "Мои занятия (TRAINER)")
    PageResponse<SessionResponse> mine(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            AuthenticatedUser trainer) {
        return queries.trainerSessions(trainer.id(), from, to, page, size).map(SessionResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Карточка занятия")
    SessionResponse get(@PathVariable UUID id, AuthenticatedUser user) {
        return SessionResponse.from(queries.session(id, user));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Создание черновика (ADMIN)", description = "Тренер проверяется во внутреннем API auth-service; "
            + "его имя сохраняется как снимок.")
    SessionResponse create(@Valid @RequestBody CreateSessionRequest r, AuthenticatedUser admin) {
        return view(commands.createDraft(new ScheduleCommandService.CreateSession(r.trainingTypeId(), r.trainerId(),
                r.title(), r.startsAt(), r.endsAt(), r.capacity())).id(), admin);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Изменение черновика (ADMIN)", description = "Только DRAFT; иначе 409 SESSION_NOT_EDITABLE.")
    SessionResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateSessionRequest r, AuthenticatedUser admin) {
        commands.editDraft(id, new ScheduleCommandService.EditSession(r.trainingTypeId(), r.trainerId(), r.title(),
                r.startsAt(), r.endsAt(), r.capacity()));
        return view(id, admin);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Публикация (ADMIN)", description = "Только будущее занятие с действующим тренером. "
            + "В той же транзакции создаётся событие SessionPublished.")
    SessionResponse publish(@PathVariable UUID id, AuthenticatedUser admin) {
        commands.publish(id);
        return view(id, admin);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Отмена (ADMIN)", description = "Опубликованное будущее занятие → SessionCancelled; "
            + "повторная отмена ничего не меняет.")
    SessionResponse cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) CancelSessionRequest r,
            AuthenticatedUser admin) {
        commands.cancel(id, r == null ? null : r.reason());
        return view(id, admin);
    }

    private SessionResponse view(UUID id, AuthenticatedUser user) {
        return SessionResponse.from(queries.session(id, user));
    }
}
