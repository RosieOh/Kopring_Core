package com.site.infrastructure.async

import com.site.independent.config.AsyncExecutors
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableAsync
class AsyncConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 알림 발송 전용 스레드 풀. 외부 API 가 느려져도 다른 비동기 작업까지 막히지 않게 용도별로 나눈다.
     *
     * 큐(1000)까지 가득 차면 알림은 버리고 경고만 남긴다.
     * 기본 정책(AbortPolicy)이면 예외가 발급 요청 스레드로 터져서, 쿠폰은 발급됐는데 API 는 500 을 응답하게 된다.
     */
    @Bean(AsyncExecutors.NOTIFICATION)
    fun notificationExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 4
            maxPoolSize = 8
            queueCapacity = 1_000
            setThreadNamePrefix("notification-")
            setRejectedExecutionHandler { _, _ -> log.warn("알림 발송 큐가 가득 차서 알림 1건을 버렸습니다.") }
            // 배포 등으로 종료될 때 이미 받은 알림 작업은 최대 10초까지 마저 처리한다
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
        }
}
