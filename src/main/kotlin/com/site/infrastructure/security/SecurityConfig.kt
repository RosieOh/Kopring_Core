package com.site.infrastructure.security

import com.site.infrastructure.redis.AccessTokenBlacklist
import com.site.infrastructure.security.handler.JwtAccessDeniedHandler
import com.site.infrastructure.security.handler.JwtAuthenticationEntryPoint
import com.site.infrastructure.security.jwt.JwtAuthenticationFilter
import com.site.infrastructure.security.jwt.JwtTokenProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtTokenProvider: JwtTokenProvider,
    private val accessTokenBlacklist: AccessTokenBlacklist,
    private val jwtAuthenticationEntryPoint: JwtAuthenticationEntryPoint,
    private val jwtAccessDeniedHandler: JwtAccessDeniedHandler,
) {

    /**
     * Spring Security Kotlin DSL.
     * `http { ... }` 는 import org.springframework.security.config.annotation.web.invoke 로 들어오는
     * 확장 함수(operator fun HttpSecurity.invoke)다. 이 import 가 빠지면 컴파일 에러가 난다.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            sessionManagement {
                sessionCreationPolicy = SessionCreationPolicy.STATELESS
            }
            authorizeHttpRequests {
                authorize(HttpMethod.POST, "/api/members", permitAll)
                authorize(HttpMethod.POST, "/api/auth/login", permitAll)
                authorize(HttpMethod.POST, "/api/auth/reissue", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize("/actuator/health", permitAll)
                authorize("/error", permitAll)
                authorize("/api/admin/**", hasRole("ADMIN"))
                authorize(anyRequest, authenticated)
            }
            exceptionHandling {
                authenticationEntryPoint = jwtAuthenticationEntryPoint
                accessDeniedHandler = jwtAccessDeniedHandler
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(
                JwtAuthenticationFilter(jwtTokenProvider, accessTokenBlacklist),
            )
        }
        return http.build()
    }

    // {bcrypt}... 형태로 저장되어 나중에 알고리즘을 바꿔도 기존 비밀번호와 호환된다
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
}
