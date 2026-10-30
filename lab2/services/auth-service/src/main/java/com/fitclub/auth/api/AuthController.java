package com.fitclub.auth.api;

import java.time.Clock;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fitclub.auth.api.dto.AuthDtos.LoginRequest;
import com.fitclub.auth.api.dto.AuthDtos.RefreshRequest;
import com.fitclub.auth.api.dto.AuthDtos.RegisterRequest;
import com.fitclub.auth.api.dto.AuthDtos.TokenResponse;
import com.fitclub.auth.api.dto.AuthDtos.UserResponse;
import com.fitclub.auth.application.LoginUseCase;
import com.fitclub.auth.application.LogoutUseCase;
import com.fitclub.auth.application.RefreshSessionUseCase;
import com.fitclub.auth.application.RegisterUserUseCase;
import com.fitclub.auth.application.TokenPair;
import com.fitclub.auth.application.UserQueryService;
import com.fitclub.common.security.AuthenticatedUser;

/** HTTP-слой аутентификации: только DTO, валидация и вызов use case'ов. */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Аутентификация", description = "Регистрация, вход, обновление и завершение сессии")
class AuthController {

    private final RegisterUserUseCase registerUser;
    private final LoginUseCase login;
    private final RefreshSessionUseCase refreshSession;
    private final LogoutUseCase logout;
    private final UserQueryService userQueries;
    private final Clock clock;

    AuthController(RegisterUserUseCase registerUser, LoginUseCase login, RefreshSessionUseCase refreshSession,
            LogoutUseCase logout, UserQueryService userQueries, Clock clock) {
        this.registerUser = registerUser;
        this.login = login;
        this.refreshSession = refreshSession;
        this.logout = logout;
        this.userQueries = userQueries;
        this.clock = clock;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Регистрация клиента", description = "Всегда назначает роль CLIENT. Email нормализуется.")
    UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return UserResponse.from(registerUser.register(
                new RegisterUserUseCase.Command(request.email(), request.password(), request.displayName())));
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Вход", description = "Возвращает access JWT (15 минут) и refresh-токен.")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return tokens(login.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Обновление сессии", description = "Ротация: старый refresh-токен становится недействительным.")
    ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return tokens(refreshSession.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirements
    @Operation(summary = "Выход", description = "Отзывает refresh-токены сессии. Уже выданный access JWT действует "
            + "до своего срока (не более 15 минут).")
    void logout(@Valid @RequestBody RefreshRequest request) {
        logout.logout(request.refreshToken());
    }

    @GetMapping("/me")
    @Operation(summary = "Текущий профиль")
    UserResponse me(AuthenticatedUser user) {
        return UserResponse.from(userQueries.profile(user.id()));
    }

    private ResponseEntity<TokenResponse> tokens(TokenPair pair) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TokenResponse.from(pair, clock.instant()));
    }
}
