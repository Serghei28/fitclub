package com.fitclub.booking;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import com.fitclub.testsupport.ArchitectureRules;

/** Архитектурные ограничения booking-service: слои, чистый домен, отсутствие зависимостей на другие сервисы. */
@AnalyzeClasses(packages = "com.fitclub.booking", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.fitclub.booking";

    @ArchTest
    static final ArchRule layers = ArchitectureRules.layers(BASE);

    @ArchTest
    static final ArchRule domainIsFrameworkFree = ArchitectureRules.domainIsFrameworkFree(BASE);

    @ArchTest
    static final ArchRule controllersUseApplicationOnly = ArchitectureRules.controllersUseApplicationOnly(BASE);

    @ArchTest
    static final ArchRule noDependencyOnOtherServices = ArchitectureRules.noDependencyOnOtherServices("booking");
}
