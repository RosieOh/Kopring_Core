package com.site.domain.coupon.entity

/** 저장하지 않고 사용 시각·만료 시각·현재 시각으로 계산한다. */
enum class IssuedCouponStatus {
    AVAILABLE,
    USED,
    EXPIRED,
}
