package com.fitclub.auth.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fitclub.auth.api.dto.AuthDtos.ChangeRolesRequest;
import com.fitclub.auth.api.dto.AuthDtos.UserResponse;
import com.fitclub.auth.application.ChangeUserRolesUseCase;
import com.fitclub.auth.application.UserQueryService;
import com.fitclub.auth.domain.Role;
import com.fitclub.common.security.AuthenticatedUser;
import com.fitclub.common.web.PageResponse;

/** Управление пользователями и ролями. Только ADMIN. */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Администрирование пользователей", description = "Только ADMIN")
class AdminUserController {

    private final UserQueryService userQueries;
    private final ChangeUserRolesUseCase changeRoles;

    AdminUserController(UserQueryService userQueries, ChangeUserRolesUseCase changeRoles) {
        this.userQueries = userQueries;
        this.changeRoles = changeRoles;
    }

    @GetMapping
    @Operation(summary = "Список и поиск пользователей", description = "Фильтр по роли и подстроке email/имени, "
            + "сортировка по email, size не больше 100.")
    PageResponse<UserResponse> list(
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return userQueries.search(role, q, page, size).map(UserResponse::from);
    }

    @PatchMapping("/{id}/roles")
    @Operation(summary = "Назначение ролей", description = "Заменяет набор ролей. Снять ADMIN с самого себя нельзя.")
    UserResponse changeRoles(@PathVariable UUID id, @Valid @RequestBody ChangeRolesRequest request,
            AuthenticatedUser admin) {
        return UserResponse.from(changeRoles.changeRoles(admin.id(), admin.roles(), id, request.roles()));
    }
}
