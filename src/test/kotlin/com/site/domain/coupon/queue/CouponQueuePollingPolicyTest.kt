package com.site.domain.coupon.queue

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import java.time.Duration

class CouponQueuePollingPolicyTest : FunSpec({

    // 배치 50명, 초당 500명 처리 추정 → 순번 1,000번은 약 2초 뒤 차례 → 그 절반(1초) 뒤에 다시 묻는다
    val policy = CouponQueuePollingPolicy(
        properties = CouponQueuePollingProperties(
            minInterval = Duration.ofMillis(300),
            maxInterval = Duration.ofSeconds(3),
            estimatedThroughputPerSecond = 500,
        ),
        queueProperties = CouponQueueProperties(batchSize = 50),
    )

    context("앞 순번은 짧게, 뒤 순번은 길게 (최소 0.3초 ~ 최대 3초)") {
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
            policy.retryAfterMillis(status) shouldBe expected
        }
    }

    test("결과가 나왔거나 대기열에 없으면 더 물을 필요가 없다") {
        policy.retryAfterMillis(CouponQueueStatus.Done(CouponQueueResult.ISSUED)) shouldBe null
        policy.retryAfterMillis(CouponQueueStatus.NotInQueue) shouldBe null
    }
})
