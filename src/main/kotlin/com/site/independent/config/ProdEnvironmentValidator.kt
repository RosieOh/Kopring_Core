package com.site.independent.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Profiles

/**
 * 운영 프로필에서 필수 환경변수가 빠졌으면, 무엇이 빠졌는지 알려주며 기동을 멈춘다.
 *
 * Spring 은 해석하지 못한 "${DB_URL}" 을 문자열 그대로 넘긴다. 그러면 "URL must start with 'jdbc'" 나
 * Base64 디코딩 오류처럼 원인과 동떨어진 에러가 엉뚱한 곳에서 난다.
 *
 * 빈을 만들기 전에 실행돼야 해서 @Component 가 아니라 EnvironmentPostProcessor 로 등록한다. (META-INF/spring.factories)
 */
class ProdEnvironmentValidator : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (!environment.acceptsProfiles(Profiles.of(PROD_PROFILE))) return

        val missing = REQUIRED_VARIABLES.filter { environment.getProperty(it).isNullOrBlank() }
        check(missing.isEmpty()) {
            "운영 프로필에 필요한 환경변수가 없습니다: ${missing.joinToString()} (application-prod.properties 참고)"
        }
    }

    companion object {
        private const val PROD_PROFILE = "prod"
        val REQUIRED_VARIABLES = listOf("DB_URL", "DB_USERNAME", "DB_PASSWORD", "REDIS_HOST", "JWT_SECRET")
    }
}
