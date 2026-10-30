package com.fitclub.auth.infrastructure.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.fitclub.auth.application.EnsureInitialAdminUseCase;
import com.fitclub.auth.infrastructure.config.AuthProperties;

/**
 * Явное bootstrap-действие при запуске: первый администратор из конфигурации
 * (FITCLUB_AUTH_INITIAL_ADMIN_EMAIL + секрет auth_initial_admin_password).
 * Без пароля ничего не делает; пароль в лог не пишется.
 */
@Component
class InitialAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialAdminBootstrap.class);

    private final AuthProperties properties;
    private final EnsureInitialAdminUseCase ensureInitialAdmin;

    InitialAdminBootstrap(AuthProperties properties, EnsureInitialAdminUseCase ensureInitialAdmin) {
        this.properties = properties;
        this.ensureInitialAdmin = ensureInitialAdmin;
    }

    @Override
    public void run(ApplicationArguments args) {
        AuthProperties.InitialAdmin admin = properties.initialAdmin();
        if (admin == null || !admin.configured()) {
            log.info("Initial admin bootstrap skipped: email or password is not configured");
            return;
        }
        EnsureInitialAdminUseCase.Result result =
                ensureInitialAdmin.ensure(admin.email(), admin.password(), admin.displayName());
        log.info("Initial admin bootstrap: {}", result);
    }
}
