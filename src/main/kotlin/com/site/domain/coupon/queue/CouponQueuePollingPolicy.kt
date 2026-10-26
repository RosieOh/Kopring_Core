package com.site.domain.coupon.queue

import com.site.domain.coupon.queue.port.CouponQueueStore
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 대기열 상태를 언제 다시 물어보면 되는지 알려준다. (응답의 retryAfterMillis)
 *
 * 모두가 0.5초마다 물으면, 1,950번째 사용자의 조회는 대부분 "아직 대기 중"이라는 같은 답을 받는다.
 * 남은 예상 대기 시간의 절반 뒤에 다시 묻게 하면, 결과를 늦게 알게 되는 일 없이 조회 수가 순번에 맞게 준다.
 *
 * 예상 대기 시간은 "내 결과가 나오기 전까지 처리될 인원 ÷ 처리량"이다.
 * 남은 수량보다 뒤에 선 사람은 내 앞사람이 다 처리될 때가 아니라 소진되는 순간 결과(소진)를 받으므로, 처리될 인원은 min(순번, 남은 수량)이다.
 * 순번만 보면 쿠폰 100장에 2,000번째 사용자에게 2초 뒤에 물으라고 하는데, 실제 결과는 100명이 처리되자마자 나온다.
 */
@Component
class CouponQueuePollingPolicy(
    private val properties: CouponQueuePollingProperties,
    private val queueProperties: CouponQueueProperties,
    private val couponQueueStore: CouponQueueStore,
) {

    /** 다시 물어볼 때까지 기다릴 시간(ms). 결과가 나왔거나 대기열에 없으면 null — 더 물을 필요가 없다 */
    fun retryAfterMillis(couponEventId: Long, status: CouponQueueStatus): Long? {
        // 남은 수량은 기다리는 사람에게만 필요하다 (Redis 조회를 아낀다)
        val remainingQuantity = if (status is CouponQueueStatus.Waiting) couponQueueStore.remainingQuantity(couponEventId) else null
        return retryAfterMillis(status, remainingQuantity)
    }

    /** @param remainingQuantity 워커가 마지막으로 확인한 남은 수량. 모르면 null (순번만으로 추정) */
    fun retryAfterMillis(status: CouponQueueStatus, remainingQuantity: Int?): Long? =
        when (status) {
            CouponQueueStatus.Processing -> properties.minInterval.toMillis()
            is CouponQueueStatus.Waiting -> {
                val processedBeforeMyResult = remainingQuantity?.let { minOf(status.position, it.toLong()) } ?: status.position
                if (processedBeforeMyResult <= queueProperties.batchSize) {
                    properties.minInterval.toMillis() // 다음 배치 안에 결과가 나온다
                } else {
                    val expectedWaitMillis = processedBeforeMyResult * 1_000 / properties.estimatedThroughputPerSecond
                    (expectedWaitMillis / 2).coerceIn(properties.minInterval.toMillis(), properties.maxInterval.toMillis())
                }
            }
            is CouponQueueStatus.Done, CouponQueueStatus.NotInQueue -> null
        }
}

/**
 * @property estimatedThroughputPerSecond 워커가 1초에 발급하는 인원 추정치. 부하 테스트로 잰 값에 맞춘다
 */
@ConfigurationProperties(prefix = "coupon.queue.polling")
data class CouponQueuePollingProperties(
    val minInterval: Duration = Duration.ofMillis(300),
    val maxInterval: Duration = Duration.ofSeconds(3),
    val estimatedThroughputPerSecond: Long = 500,
)
