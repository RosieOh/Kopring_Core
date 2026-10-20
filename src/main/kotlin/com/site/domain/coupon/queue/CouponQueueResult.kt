package com.site.domain.coupon.queue

/**
 * 대기열에서 꺼내져 처리된 결과.
 * @property closesQueue 이 결과가 나오면 뒤에 선 사람도 모두 같은 결과가 되므로 대기열을 닫는다.
 */
enum class CouponQueueResult(
    val closesQueue: Boolean,
) {
    ISSUED(closesQueue = false),
    ALREADY_ISSUED(closesQueue = false),
    SOLD_OUT(closesQueue = true),
    ENDED(closesQueue = true),
    FAILED(closesQueue = false),
}
