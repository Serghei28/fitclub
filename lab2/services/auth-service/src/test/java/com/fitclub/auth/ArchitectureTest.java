package com.fitclub.auth;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import com.fitclub.testsupport.ArchitectureRules;

/** Архитектурные ограничения auth-service: слои, чистый домен, отсутствие зависимостей на другие сервисы. */
@AnalyzeClasses(packages = "com.fitclub.auth", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.fitclub.auth";

    @ArchTest
    static final ArchRule layers = ArchitectureRules.layers(BASE);

    @ArchTest
    static final ArchRule domainIsFrameworkFree = ArchitectureRules.domainIsFrameworkFree(BASE);

    @ArchTest
    static final ArchRule controllersUseApplicationOnly = ArchitectureRules.controllersUseApplicationOnly(BASE);

    @ArchTest
    static final ArchRule noDependencyOnOtherServices = ArchitectureRules.noDependencyOnOtherServices("auth");
}
