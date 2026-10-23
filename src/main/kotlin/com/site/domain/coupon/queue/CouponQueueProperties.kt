package com.site.domain.coupon.queue

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * @property batchSize 워커가 한 번에 대기열에서 꺼내 발급하는 인원. (주기 × 배치 크기 = 초당 처리량의 상한)
 * @property keyTtl 대기열 관련 Redis 키의 만료 시간. 이벤트가 끝난 뒤 키가 영원히 남지 않게 한다.
 * @property processingLease 꺼낸 회원을 처리 중으로 잡아 두는 시간. 이 시간이 지나도록 결과가 없으면
 *   처리하던 워커가 죽은 것으로 보고 다음 주기에 다시 처리한다. 한 배치 처리 시간보다 넉넉해야 한다.
 */
@ConfigurationProperties(prefix = "coupon.queue")
data class CouponQueueProperties(
    val batchSize: Long = 50,
    val keyTtl: Duration = Duration.ofDays(1),
    val processingLease: Duration = Duration.ofSeconds(30),
)
