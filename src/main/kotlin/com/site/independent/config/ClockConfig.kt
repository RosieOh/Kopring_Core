package com.site.independent.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

/**
 * LocalDateTime.now() 를 코드 곳곳에서 직접 부르면 "시간"을 테스트에서 통제할 수 없다.
 * Clock 을 빈으로 두고 LocalDateTime.now(clock) 으로 쓰면, 테스트에서 고정된 시각을 주입할 수 있다.
 *
 * 시간대는 서버 OS 설정에 맡기지 않고 서비스 시간대로 고정한다.
 * 이벤트 시각은 시간대 정보가 없는 LocalDateTime 이라, 서버가 UTC 로 돌면 "10시 시작"이 9시간 어긋나게 해석된다.
 */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.system(SERVICE_ZONE)

    companion object {
        /** 서비스 시간대. 저장되는 모든 LocalDateTime 은 이 시간대 기준이다. */
        val SERVICE_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
