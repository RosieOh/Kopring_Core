package com.site.support

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * 통합 테스트용 메타 애너테이션.
 * 모든 통합 테스트가 같은 설정 조합을 쓰게 해서 스프링 컨텍스트(와 Redis 컨테이너)를 한 번만 띄우고 재사용한다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestContainersConfig::class, TestAuthHelper::class, FakeAlimtalkSender::class)
annotation class IntegrationTest
