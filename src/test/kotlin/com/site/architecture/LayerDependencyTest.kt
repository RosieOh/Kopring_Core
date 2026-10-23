package com.site.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * 계층 의존 방향을 테스트로 강제한다. 규칙을 어기는 import 가 생기면 CI 에서 깨진다.
 *
 *   presentation ──▶ domain ◀── infrastructure
 *          └──────────┴──────────────┴──▶ independent (계층 공통)
 */
@AnalyzeClasses(packages = ["com.site"], importOptions = [ImportOption.DoNotIncludeTests::class])
class LayerDependencyTest {

    @ArchTest
    fun `domain 은 infrastructure 와 presentation 을 모른다`(classes: JavaClasses) {
        noClasses().that().resideInAPackage("com.site.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("com.site.infrastructure..", "com.site.presentation..")
            .because("Redis·JWT·알림톡 같은 기술은 domain 의 포트(인터페이스)를 infrastructure 가 구현하는 방식으로만 연결한다")
            .check(classes)
    }

    @ArchTest
    fun `independent 는 어느 계층에도 의존하지 않는다`(classes: JavaClasses) {
        noClasses().that().resideInAPackage("com.site.independent..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.site.domain..",
                "com.site.infrastructure..",
                "com.site.presentation..",
            )
            .check(classes)
    }

    @ArchTest
    fun `infrastructure 는 presentation 을 모른다`(classes: JavaClasses) {
        noClasses().that().resideInAPackage("com.site.infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("com.site.presentation..")
            .check(classes)
    }
}
