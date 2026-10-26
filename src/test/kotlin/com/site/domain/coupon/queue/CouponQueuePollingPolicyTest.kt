package com.site.domain.coupon.queue

import com.site.domain.coupon.queue.port.CouponQueueStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration

class CouponQueuePollingPolicyTest : FunSpec({

    val couponQueueStore = mockk<CouponQueueStore>()

    // 배치 50명, 초당 500명 처리 추정 → 순번 1,000번은 약 2초 뒤 차례 → 그 절반(1초) 뒤에 다시 묻는다
    val policy = CouponQueuePollingPolicy(
        properties = CouponQueuePollingProperties(
            minInterval = Duration.ofMillis(300),
            maxInterval = Duration.ofSeconds(3),
            estimatedThroughputPerSecond = 500,
        ),
        queueProperties = CouponQueueProperties(batchSize = 50),
        couponQueueStore = couponQueueStore,
    )

    context("남은 수량을 모르면 순번만 본다 — 앞 순번은 짧게, 뒤 순번은 길게 (최소 0.3초 ~ 최대 3초)") {
        withData(
            mapOf(
                "처리 중 → 0.3초" to (CouponQueueStatus.Processing to 300L),
                "1번 (다음 배치) → 0.3초" to (CouponQueueStatus.Waiting(1) to 300L),
                "50번 (다음 배치) → 0.3초" to (CouponQueueStatus.Waiting(50) to 300L),
                "200번 (예상 0.4초의 절반이 최소보다 짧음) → 0.3초" to (CouponQueueStatus.Waiting(200) to 300L),
                "1,000번 (예상 2초) → 1초" to (CouponQueueStatus.Waiting(1_000) to 1_000L),
                "2,000번 (예상 4초) → 2초" to (CouponQueueStatus.Waiting(2_000) to 2_000L),
                "10,000번 (예상 20초) → 최대 3초" to (CouponQueueStatus.Waiting(10_000) to 3_000L),
            ),
        ) { (status, expected) ->
            policy.retryAfterMillis(status, remainingQuantity = null) shouldBe expected
        }
    }

    context("남은 수량보다 뒤에 선 사람은 소진되는 순간 결과가 나오므로 남은 수량만큼만 기다린다") {
        withData(
            mapOf(
                "2,000번, 남은 50장 → 다음 배치에 소진 → 0.3초" to (2_000L to 50 to 300L),
                "2,000번, 남은 1,000장 (예상 2초) → 1초 (순번만 보면 2초)" to (2_000L to 1_000 to 1_000L),
                "2,000번, 남은 0장 (이미 소진, 곧 정리됨) → 0.3초" to (2_000L to 0 to 300L),
                "1,000번, 남은 5,000장 → 순번이 더 앞이므로 순번 기준 1초" to (1_000L to 5_000 to 1_000L),
            ),
        ) { (input, expected) ->
            val (position, remaining) = input
            policy.retryAfterMillis(CouponQueueStatus.Waiting(position), remainingQuantity = remaining) shouldBe expected
        }
    }

    test("결과가 나왔거나 대기열에 없으면 더 물을 필요가 없다") {
        policy.retryAfterMillis(CouponQueueStatus.Done(CouponQueueResult.ISSUED), remainingQuantity = null) shouldBe null
        policy.retryAfterMillis(CouponQueueStatus.NotInQueue, remainingQuantity = null) shouldBe null
    }

    test("남은 수량은 기다리는 사람에게만 필요하므로 그때만 저장소에서 읽는다") {
        every { couponQueueStore.remainingQuantity(1L) } returns 50

        policy.retryAfterMillis(1L, CouponQueueStatus.Waiting(2_000)) shouldBe 300L
        policy.retryAfterMillis(1L, CouponQueueStatus.Processing) shouldBe 300L

        verify(exactly = 1) { couponQueueStore.remainingQuantity(1L) }
    }
})
