package com.site.domain.coupon.queue

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 워커를 주기적으로 실행한다. 테스트에서는 꺼 두고(coupon.queue.scheduler.enabled=false) 워커를 직접 부른다.
 * fixedDelay: 이전 실행이 끝난 뒤부터 간격을 잰다. 처리가 오래 걸려도 실행이 겹치지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "coupon.queue.scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class CouponQueueScheduler(
    private val couponQueueWorker: CouponQueueWorker,
) {

    @Scheduled(fixedDelayString = "\${coupon.queue.scheduler.interval-millis:200}")
    fun run() {
        couponQueueWorker.processAll()
    }
}
