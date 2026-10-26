package com.site.presentation.coupon.stream

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** SSE 로 연결된 사용자의 대기열 상태를 주기적으로 확인해 밀어 준다. 테스트에서는 꺼 두고 직접 부른다. */
@Component
@ConditionalOnProperty(prefix = "coupon.queue.stream.scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class CouponQueueStreamScheduler(
    private val streams: CouponQueueStatusStreams,
) {

    @Scheduled(fixedDelayString = "\${coupon.queue.stream.scheduler.interval-millis:500}")
    fun run() {
        streams.pushAll()
    }
}
