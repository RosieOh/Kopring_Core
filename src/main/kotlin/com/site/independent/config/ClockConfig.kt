package com.site.independent.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * LocalDateTime.now() 를 코드 곳곳에서 직접 부르면 "시간"을 테스트에서 통제할 수 없다.
 * Clock 을 빈으로 두고 LocalDateTime.now(clock) 으로 쓰면, 테스트에서 고정된 시각을 주입할 수 있다.
 */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
