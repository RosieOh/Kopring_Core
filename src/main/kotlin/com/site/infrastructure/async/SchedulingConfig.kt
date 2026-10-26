package com.site.infrastructure.async

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration
@EnableScheduling
class SchedulingConfig {

    /**
     * 가상 스레드를 켜도 스케줄러는 플랫폼 스레드 풀(spring.task.scheduling.pool.size)로 둔다.
     *
     * spring.threads.virtual.enabled=true 이면 스프링 부트는 SimpleAsyncTaskScheduler 를 쓰는데,
     * 이 스케줄러는 fixed-delay 작업을 "스레드 하나"에서 차례로 돌린다. (fixed-delay 의미를 지키려고. spring-context 6.2 javadoc)
     * 대기열 워커·알림 Outbox·SSE 스케줄러가 모두 fixed-delay 라서, 알림 발송(외부 API)이 느려지면 대기열 워커까지 밀린다.
     * TaskScheduler 빈을 직접 등록하면 스프링 부트의 자동 설정은 물러난다.
     */
    @Bean
    fun taskScheduler(builder: ThreadPoolTaskSchedulerBuilder): ThreadPoolTaskScheduler = builder.build()
}
