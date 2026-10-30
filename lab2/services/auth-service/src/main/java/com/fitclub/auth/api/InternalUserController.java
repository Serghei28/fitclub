package com.fitclub.auth.api;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fitclub.auth.api.dto.AuthDtos.TrainerStatusResponse;
import com.fitclub.auth.application.UserQueryService;

/**
 * Внутренний API для schedule-service. Доступ только с X-Service-Token направления schedule → auth
 * (ServiceTokenFilter, fitclub.internal.clients). Gateway этот маршрут не публикует.
 */
@RestController
@RequestMapping("/internal/users")
@Tag(name = "Внутренний API", description = "Только для сервисов (X-Service-Token), не для браузера")
@SecurityRequirement(name = "serviceToken")
class InternalUserController {

    private final UserQueryService userQueries;

    InternalUserController(UserQueryService userQueries) {
        this.userQueries = userQueries;
    }

    @GetMapping("/{id}/trainer-status")
    @Operation(summary = "Статус тренера", description = "Действующий ли пользователь и есть ли у него роль TRAINER; "
            + "display_name для снимка в расписании. 404, если пользователя нет.")
    TrainerStatusResponse trainerStatus(@PathVariable UUID id) {
        return TrainerStatusResponse.from(userQueries.trainerStatus(id));
    }
}
