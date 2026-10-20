package com.site.infrastructure.database

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

/**
 * @EnableJpaAuditing 을 SiteApplication 에 붙이면 @WebMvcTest 같은 슬라이스 테스트에서
 * JPA 메타모델이 없어 컨텍스트가 깨진다. 별도 설정 클래스로 분리한다.
 */
@Configuration
@EnableJpaAuditing
class JpaAuditingConfig
