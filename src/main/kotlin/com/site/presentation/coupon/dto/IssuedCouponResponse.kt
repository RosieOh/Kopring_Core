package com.site.presentation.coupon.dto

import com.site.domain.coupon.entity.IssuedCoupon
import java.time.LocalDateTime

data class IssuedCouponResponse(
    val id: Long,
    val couponEventId: Long,
    val issuedAt: LocalDateTime,
)

fun IssuedCoupon.toResponse(): IssuedCouponResponse =
    IssuedCouponResponse(
        id = id,
        couponEventId = couponEventId,
        issuedAt = createdAt,
    )
