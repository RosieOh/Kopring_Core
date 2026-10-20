package com.site.support

import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 테스트 사이에 Redis 상태(대기열, 토큰 등)가 남지 않게 비운다. */
fun StringRedisTemplate.flushDb() {
    requireNotNull(connectionFactory).connection.use { it.serverCommands().flushDb() }
}

/**
 * task 를 taskCount 번, threadCount 개 스레드로 "동시에 출발"시켜 실행한다.
 * 작업 중 하나라도 예외가 나면 테스트를 실패시킨다.
 */
fun runConcurrently(taskCount: Int, threadCount: Int = 32, task: (index: Int) -> Unit) {
    val executor = Executors.newFixedThreadPool(threadCount)
    val startSignal = CountDownLatch(1)
    val doneSignal = CountDownLatch(taskCount)
    val errors = ConcurrentLinkedQueue<Throwable>()

    repeat(taskCount) { index ->
        executor.execute {
            try {
                startSignal.await()
                task(index)
            } catch (e: Throwable) {
                errors += e
            } finally {
                doneSignal.countDown()
            }
        }
    }

    startSignal.countDown()
    check(doneSignal.await(1, TimeUnit.MINUTES)) { "동시 작업이 1분 안에 끝나지 않았습니다" }
    executor.shutdown()

    errors.firstOrNull()?.let { throw AssertionError("동시 작업 중 예외 ${errors.size}건", it) }
}
