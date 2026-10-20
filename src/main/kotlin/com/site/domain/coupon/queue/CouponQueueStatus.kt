package com.site.domain.coupon.queue

/** 대기열 안에서 내 상태. 셋 중 하나이며, 각 상태가 가진 정보가 다르다. (sealed) */
sealed interface CouponQueueStatus {

    /** @property position 1부터 시작하는 내 순번 */
    data class Waiting(val position: Long) : CouponQueueStatus

    data class Done(val result: CouponQueueResult) : CouponQueueStatus

    data object NotInQueue : CouponQueueStatus
}
