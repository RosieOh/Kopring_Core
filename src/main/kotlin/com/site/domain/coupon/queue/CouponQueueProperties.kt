package com.site.domain.coupon.queue

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * @property batchSize 워커가 한 번에 대기열에서 꺼내 발급하는 인원. (주기 × 배치 크기 = 초당 처리량의 상한)
 * @property keyTtl 대기열 관련 Redis 키의 만료 시간. 이벤트가 끝난 뒤 키가 영원히 남지 않게 한다.
 */
@ConfigurationProperties(prefix = "coupon.queue")
data class CouponQueueProperties(
    val batchSize: Long = 50,
    val keyTtl: Duration = Duration.ofDays(1),
)
