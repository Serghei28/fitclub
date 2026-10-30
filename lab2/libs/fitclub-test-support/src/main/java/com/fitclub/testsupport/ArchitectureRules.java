package com.fitclub.testsupport;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import java.util.List;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Общие ArchUnit-правила слоёв FitClub. Каждый сервис вызывает их для своего базового пакета.
 * Слои внутри сервиса: api → application → domain; infrastructure реализует порты application.
 */
public final class ArchitectureRules {

    private static final List<String> SERVICES =
            List.of("auth", "schedule", "membership", "booking", "payment", "notification");

    private ArchitectureRules() {
    }

    /** Направление зависимостей между слоями (DIP: application не знает об адаптерах). */
    public static ArchRule layers(String basePackage) {
        return layeredArchitecture().consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true)
                .layer("api").definedBy(basePackage + ".api..")
                .layer("application").definedBy(basePackage + ".application..")
                .layer("domain").definedBy(basePackage + ".domain..")
                .layer("infrastructure").definedBy(basePackage + ".infrastructure..")
                .whereLayer("api").mayNotBeAccessedByAnyLayer()
                .whereLayer("infrastructure").mayNotBeAccessedByAnyLayer()
                .whereLayer("application").mayOnlyBeAccessedByLayers("api", "infrastructure")
                .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "infrastructure", "api");
    }

    /** Домен не зависит от транспорта и хранения: Spring Web, AMQP, JPA, Spring Data, JDBC. */
    public static ArchRule domainIsFrameworkFree(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..",
                        "org.springframework.amqp..", "jakarta.persistence..", "org.hibernate..",
                        "org.springframework.data..", "org.springframework.jdbc..", "tools.jackson..")
                .allowEmptyShould(true)
                .because("domain содержит только бизнес-правила (SRP, DIP)");
    }

    /** Контроллеры не обращаются к репозиториям и брокеру напрямую — только через use case'ы. */
    public static ArchRule controllersUseApplicationOnly(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + ".api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        basePackage + ".infrastructure..", "org.springframework.data..",
                        "org.springframework.jdbc..", "org.springframework.amqp..", "jakarta.persistence..")
                .allowEmptyShould(true)
                .because("Controller отвечает только за HTTP и DTO (SRP)");
    }

    /** Ни один бизнес-сервис не импортирует код другого: взаимодействие только по HTTP и RabbitMQ. */
    public static ArchRule noDependencyOnOtherServices(String serviceName) {
        String[] foreign = SERVICES.stream()
                .filter(name -> !name.equals(serviceName))
                .map(name -> "com.fitclub." + name + "..")
                .toArray(String[]::new);
        return noClasses().that().resideInAPackage("com.fitclub." + serviceName + "..")
                .should().dependOnClassesThat().resideInAnyPackage(foreign)
                .allowEmptyShould(true)
                .because("сервисы взаимодействуют только через HTTP и RabbitMQ");
    }
}
