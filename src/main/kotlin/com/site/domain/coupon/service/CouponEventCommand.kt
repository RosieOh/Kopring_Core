package com.site.domain.coupon.service

import com.site.domain.coupon.entity.Discount
import com.site.domain.coupon.entity.DiscountType
import com.site.domain.coupon.entity.EventPeriod
import java.time.LocalDateTime

/**
 * 서비스가 받는 입력. 컨트롤러의 요청 DTO 를 그대로 받으면 domain 이 presentation 에 의존하게 되므로
 * domain 쪽에 입력 모델을 따로 두고, 컨트롤러가 DTO → Command 로 변환해서 넘긴다.
 */
data class CouponEventCommand(
    val name: String,
    val discountType: DiscountType,
    val discountValue: Int,
    val totalQuantity: Int,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
) {
    fun toDiscount(): Discount = Discount(type = discountType, value = discountValue)

    fun toPeriod(): EventPeriod = EventPeriod(startAt = startAt, endAt = endAt)
}
