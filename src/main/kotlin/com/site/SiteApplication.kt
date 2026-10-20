package com.site

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

// JWT 로 직접 인증하므로 Spring Boot 기본 인메모리 사용자(+ 랜덤 비밀번호 로그)는 끈다
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
class SiteApplication

fun main(args: Array<String>) {
    runApplication<SiteApplication>(*args)
}
