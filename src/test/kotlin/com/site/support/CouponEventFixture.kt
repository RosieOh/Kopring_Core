package com.site.support

import com.site.domain.coupon.entity.CouponEvent
import com.site.domain.coupon.entity.Discount
import com.site.domain.coupon.entity.DiscountType
import com.site.domain.coupon.entity.EventPeriod
import java.time.LocalDateTime

/** 기본값은 "지금 진행 중인 100장짜리 이벤트". 테스트에 필요한 값만 바꿔서 쓴다. */
fun couponEventFixture(
    name: String = "선착순 5천원 할인",
    discount: Discount = Discount(DiscountType.FIXED_AMOUNT, 5_000),
    startAt: LocalDateTime = LocalDateTime.now().minusHours(1),
    endAt: LocalDateTime = LocalDateTime.now().plusDays(1),
    totalQuantity: Int = 100,
): CouponEvent =
    CouponEvent(
        name = name,
        discount = discount,
        period = EventPeriod(startAt = startAt, endAt = endAt),
        totalQuantity = totalQuantity,
    )
