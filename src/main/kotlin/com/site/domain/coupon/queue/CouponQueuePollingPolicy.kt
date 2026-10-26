package com.site.domain.coupon.queue

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 대기열 상태를 언제 다시 물어보면 되는지 알려준다. (응답의 retryAfterMillis)
 *
 * 모두가 0.5초마다 물으면, 1,950번째 사용자의 조회는 대부분 "아직 대기 중"이라는 같은 답을 받는다.
 * 남은 예상 대기 시간의 절반 뒤에 다시 묻게 하면, 결과를 늦게 알게 되는 일 없이 조회 수가 순번에 맞게 준다.
 */
@Component
class CouponQueuePollingPolicy(
    private val properties: CouponQueuePollingProperties,
    private val queueProperties: CouponQueueProperties,
) {

    /** 다시 물어볼 때까지 기다릴 시간(ms). 결과가 나왔거나 대기열에 없으면 null — 더 물을 필요가 없다 */
    fun retryAfterMillis(status: CouponQueueStatus): Long? =
        when (status) {
            CouponQueueStatus.Processing -> properties.minInterval.toMillis()
            is CouponQueueStatus.Waiting ->
                if (status.position <= queueProperties.batchSize) {
                    properties.minInterval.toMillis() // 다음 배치에 들어가는 순번
                } else {
                    val expectedWaitMillis = status.position * 1_000 / properties.estimatedThroughputPerSecond
                    (expectedWaitMillis / 2).coerceIn(properties.minInterval.toMillis(), properties.maxInterval.toMillis())
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
