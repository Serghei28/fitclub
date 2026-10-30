package com.fitclub.gateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import com.fitclub.testsupport.ArchitectureRules;

/** Gateway — инфраструктура маршрутизации: без кода сервисов, БД и брокера. */
@AnalyzeClasses(packages = "com.fitclub.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class GatewayArchitectureTest {

    @ArchTest
    static final ArchRule noDependencyOnServices = ArchitectureRules.noDependencyOnOtherServices("gateway");

    @ArchTest
    static final ArchRule noPersistenceOrMessaging = noClasses().that().resideInAPackage("com.fitclub.gateway..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.springframework.data..",
                    "org.springframework.jdbc..", "org.springframework.amqp..")
            .because("Gateway не хранит бизнес-данные и не координирует бизнес-сценарии");
}
