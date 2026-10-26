package com.site.infrastructure.async

import com.site.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 가상 스레드를 켜도 스케줄러는 플랫폼 스레드 풀이어야 한다.
 * 스프링 부트 기본값(SimpleAsyncTaskScheduler)은 fixed-delay 작업을 스레드 하나에서 차례로 돌려서, 대기열 워커가 알림 발송 뒤에 밀린다.
 */
@IntegrationTest
class SchedulingConfigTest @Autowired constructor(
    private val taskScheduler: TaskScheduler,
    @Value("\${spring.threads.virtual.enabled}") private val virtualThreadsEnabled: Boolean,
) {

    @Test
    fun `가상 스레드를 켜도 스케줄러는 스레드 3개짜리 플랫폼 스레드 풀이다`() {
        assertEquals(true, virtualThreadsEnabled)
        val scheduler = taskScheduler as ThreadPoolTaskScheduler
        assertEquals(3, scheduler.scheduledThreadPoolExecutor.corePoolSize)

        val runsOnVirtualThread = CompletableFuture<Boolean>()
        scheduler.schedule({ runsOnVirtualThread.complete(Thread.currentThread().isVirtual) }, Instant.now())
        assertFalse(runsOnVirtualThread.get(5, TimeUnit.SECONDS))
    }
}
