plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.spring") version "2.1.10"
    kotlin("plugin.jpa") version "2.1.10"
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.sonarqube") version "6.0.1.5171"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

group = "com"
version = "0.0.1-SNAPSHOT"
description = "site"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Boot 3.4.1 기본값(1.20.4)은 Docker Engine 29+ 가 거부하는 구버전 API 로 접속한다
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.github.ulisesbocchio:jasypt-spring-boot-starter:3.0.5")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")
    implementation("io.jsonwebtoken:jjwt:0.12.5")

    implementation("org.springframework.boot:spring-boot-starter-aop")

    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.redisson:redisson-spring-boot-starter:3.34.1")

    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("net.nurigo:sdk:4.3.0")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")

    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Redisson(Netty)이 macOS DNS 설정을 읽는 네이티브 라이브러리. classifier 가 없으면 빈 jar 만 받아진다.
    // 버전은 Spring Boot 가 관리하는 Netty 버전을 따른다.
    runtimeOnly("io.netty:netty-resolver-dns-native-macos::osx-aarch_64")
    runtimeOnly("io.netty:netty-resolver-dns-native-macos::osx-x86_64")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:mysql")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.kotest:kotest-framework-datatest:5.9.1")
    testImplementation("org.awaitility:awaitility-kotlin")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

tasks.jar {
    enabled = false
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // 운영 서버(UTC)와 같은 조건으로 돌린다. 서버 시간대에 기대는 코드가 생기면 테스트가 깨진다.
    jvmArgs("-Duser.timezone=UTC")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

ktlint {
    // 플러그인 기본 엔진(1.0.x)은 Kotlin 2.x 컴파일러와 충돌한다. Kotlin 2.1 기반 엔진을 쓴다.
    version.set("1.5.0")
}

// tasks.register("installGitHooks") {
//    group = "gitHooks"
//    description = "Install or update Git Hooks."
//
//    doLast {
//        val hooksDir = file(".git/hooks")
//        val scriptsDir = file("scripts")
//
//        scriptsDir.listFiles()?.forEach { script ->
//            val hookFile = file("${hooksDir}/${script.name}")
//
//            script.copyTo(hookFile, overwrite = true)
//            hookFile.setExecutable(true)
//
//            println("Installed Git Hooks: ${hookFile.name}")
//        }
//    }
// }
