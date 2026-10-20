package com.site.domain.coupon.event

/**
 * 쿠폰이 발급됐다는 사실. 발급 트랜잭션 안에서 발행되고, 커밋된 뒤에 리스너가 받는다.
 * 발급 로직은 "누가 이 사실에 반응하는지(알림, 통계 등)"를 몰라도 된다.
 */
data class CouponIssuedEvent(
    val issuedCouponId: Long,
    val couponEventId: Long,
    val memberId: Long,
)
