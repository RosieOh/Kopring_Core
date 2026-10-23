package com.site.domain.coupon.entity

/**
 * 쿠폰을 어떤 방식으로 나눠주는 이벤트인지. 등록할 때 정하고, 시작한 뒤에는 바꿀 수 없다.
 *
 * 두 방식을 한 이벤트에 동시에 열어 두면, 대기열에 선 사람보다 즉시 발급 API 를 직접 부른 사람이 먼저 받는다. (새치기)
 */
enum class CouponIssueMode {
    /** 즉시 발급 — 요청한 순간 락을 잡고 바로 발급 */
    DIRECT,

    /** 대기열 — 줄을 세우고 워커가 앞사람부터 발급 */
    QUEUE,
}
