package com.site.domain.coupon.entity

/**
 * DB 에 저장하지 않고 "현재 시각 + 발급 수량"으로 그때그때 계산한다.
 * 저장해두면 시작/종료 시각이 지날 때마다 상태를 바꿔줄 배치가 필요해진다.
 */
enum class CouponEventStatus {
    /** 시작 전 */
    UPCOMING,

    /** 진행 중 (발급 가능) */
    ONGOING,

    /** 진행 기간이지만 수량 소진 */
    SOLD_OUT,

    /** 종료 */
    ENDED,
}
