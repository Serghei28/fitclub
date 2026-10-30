package com.fitclub.payment;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import com.fitclub.testsupport.ArchitectureRules;

/** Архитектурные ограничения payment-service: слои, чистый домен, отсутствие зависимостей на другие сервисы. */
@AnalyzeClasses(packages = "com.fitclub.payment", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.fitclub.payment";

    @ArchTest
    static final ArchRule layers = ArchitectureRules.layers(BASE);

    @ArchTest
    static final ArchRule domainIsFrameworkFree = ArchitectureRules.domainIsFrameworkFree(BASE);

    @ArchTest
    static final ArchRule controllersUseApplicationOnly = ArchitectureRules.controllersUseApplicationOnly(BASE);

    @ArchTest
    static final ArchRule noDependencyOnOtherServices = ArchitectureRules.noDependencyOnOtherServices("payment");
}
