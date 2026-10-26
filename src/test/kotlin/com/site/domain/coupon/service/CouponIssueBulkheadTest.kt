package com.site.domain.coupon.service

import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import com.site.support.runConcurrently
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CouponIssueBulkheadTest : FunSpec({

    val meterRegistry = SimpleMeterRegistry()

    fun bulkhead(maxConcurrent: Int, maxWait: Duration = Duration.ofSeconds(5)) =
        CouponIssueBulkhead(CouponIssueProperties(bulkhead = CouponIssueProperties.Bulkhead(maxConcurrent, maxWait)), meterRegistry)

    test("동시에 실행되는 작업은 maxConcurrent 개를 넘지 않는다") {
        val bulkhead = bulkhead(maxConcurrent = 3)
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()

        runConcurrently(taskCount = 50, threadCount = 16) {
            bulkhead.execute {
                maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                Thread.sleep(5)
                running.decrementAndGet()
            }
        }

        maxRunning.get() shouldBe 3
    }

    test("자리가 나지 않으면 maxWait 만큼 기다린 뒤 COUPON_ISSUE_BUSY 로 거절한다") {
        val bulkhead = bulkhead(maxConcurrent = 1, maxWait = Duration.ofMillis(200))
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = CompletableFuture.runAsync {
            bulkhead.execute {
                occupied.countDown()
                release.await()
            }
        }
        occupied.await(5, TimeUnit.SECONDS)

        val startedAt = System.nanoTime()
        val error = shouldThrow<ServiceException> { bulkhead.execute { } }

        error.errorCode shouldBe ErrorCode.COUPON_ISSUE_BUSY
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) shouldBeGreaterThanOrEqual 200L
        release.countDown()
        holder.get(5, TimeUnit.SECONDS)
    }

    test("작업이 예외로 끝나도 자리를 돌려준다") {
        val bulkhead = bulkhead(maxConcurrent = 1, maxWait = Duration.ofMillis(100))

        shouldThrow<IllegalStateException> { bulkhead.execute { error("발급 실패") } }

        bulkhead.execute { "다음 요청" } shouldBe "다음 요청"
    }

    test("기다린 시간을 결과별로 기록한다") {
        val bulkhead = bulkhead(maxConcurrent = 1)

        bulkhead.execute { }

        meterRegistry.find("coupon.issue.bulkhead.wait").tag("result", "acquired").timer()?.count() shouldBe 1L
    }
})
