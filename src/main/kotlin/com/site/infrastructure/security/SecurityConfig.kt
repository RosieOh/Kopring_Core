package com.site.infrastructure.security

import com.site.domain.auth.port.AccessTokenBlacklist
import com.site.infrastructure.security.handler.JwtAccessDeniedHandler
import com.site.infrastructure.security.handler.JwtAuthenticationEntryPoint
import com.site.infrastructure.security.jwt.JwtAuthenticationFilter
import com.site.infrastructure.security.jwt.JwtTokenProvider
import jakarta.servlet.DispatcherType
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
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher

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
                // SSE 같은 비동기 응답이 끝날 때 한 번 더 일어나는 ASYNC 디스패치. 처음 요청(REQUEST)에서 이미 인가를 통과했다.
                // 막으면 이미 보내기 시작한 응답에 인가 실패가 겹쳐 "response is already committed" 오류가 난다.
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ASYNC), permitAll)
                authorize(HttpMethod.POST, "/api/members", permitAll)
                authorize(HttpMethod.POST, "/api/auth/login", permitAll)
                authorize(HttpMethod.POST, "/api/auth/reissue", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize("/actuator/health", permitAll)
                // Prometheus 수집용. 운영 프로필은 health 만 노출해서 이 경로 자체가 없다 (운영에서 노출하려면 관리 포트를 분리한다)
                authorize("/actuator/prometheus", permitAll)
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
