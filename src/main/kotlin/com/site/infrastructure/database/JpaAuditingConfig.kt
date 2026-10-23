package com.site.infrastructure.database

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import java.time.Clock
import java.time.LocalDateTime
import java.util.Optional

/**
 * @EnableJpaAuditing 을 SiteApplication 에 붙이면 @WebMvcTest 같은 슬라이스 테스트에서
 * JPA 메타모델이 없어 컨텍스트가 깨진다. 별도 설정 클래스로 분리한다.
 *
 * createdAt / updatedAt 도 서비스 Clock 으로 찍는다. 기본 구현은 LocalDateTime.now() (= 서버 OS 시간대)라서
 * 서버가 UTC 면 이벤트 시각(서비스 시간대)과 기준이 섞인다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
class JpaAuditingConfig {

    @Bean
    fun auditingDateTimeProvider(clock: Clock): DateTimeProvider = DateTimeProvider { Optional.of(LocalDateTime.now(clock)) }
}
