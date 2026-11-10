/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit 架构测试：固化 Rules 04 的模块依赖方向与分层边界。
 */
@AnalyzeClasses(packages = "com.kura.wires")
class ArchitectureTest {

    @ArchTest
    static final ArchRule apiIsFrameworkFree = noClasses()
            .that().resideInAnyPackage("com.kura.wires.model..", "com.kura.wires.engine..",
                    "com.kura.wires.exception..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "org.hibernate..", "jakarta.persistence..",
                    "jakarta.validation..");

    @ArchTest
    static final ArchRule coreDoesNotDependOnRestOrComponents = noClasses()
            .that().resideInAPackage("com.kura.wires.core..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.kura.wires.rest..", "com.kura.wires.components..");

    @ArchTest
    static final ArchRule componentsDoNotDependOnRest = noClasses()
            .that().resideInAPackage("com.kura.wires.components..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.kura.wires.rest..");

    @ArchTest
    static final ArchRule restIsNotDependedUpon = noClasses()
            .that().resideOutsideOfPackage("com.kura.wires.rest..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.kura.wires.rest..");

    @ArchTest
    static final ArchRule controllersOnlyLiveInRestControllerPackage = classes()
            .that().areAnnotatedWith(RestController.class)
            // 发布服务器（kura-edge-release-server，RFC 009）是唯一允许的第二个 Controller 宿主：
            // 它不依赖 core/components/rest，只对外提供 jar 与 feed.json，与业务分层无关。
            .should().resideInAnyPackage("com.kura.wires.rest.controller..", "com.kura.wires.release..");

    @ArchTest
    static final ArchRule restMustNotTouchRepositoriesDirectly = noClasses()
            .that().resideInAPackage("com.kura.wires.rest..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("com.kura.wires.core.config.ComponentConfigurationCrudRepository")
            .orShould().haveFullyQualifiedName("com.kura.wires.core.snapshot.WireGraphSnapshotRepository");
}
