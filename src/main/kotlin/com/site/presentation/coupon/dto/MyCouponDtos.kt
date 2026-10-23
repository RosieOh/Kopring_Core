package com.site.presentation.coupon.dto

import com.site.domain.coupon.entity.IssuedCouponStatus
import com.site.domain.coupon.service.CouponUsage
import com.site.domain.coupon.service.OwnedCoupon
import jakarta.validation.constraints.Positive
import java.time.LocalDateTime

data class MyCouponResponse(
    val id: Long,
    val couponEventId: Long,
    val eventName: String,
    val discountText: String,
    val status: IssuedCouponStatus,
    val issuedAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val usedAt: LocalDateTime?,
)

fun OwnedCoupon.toResponse(now: LocalDateTime): MyCouponResponse =
    MyCouponResponse(
        id = coupon.id,
        couponEventId = couponEvent.id,
        eventName = couponEvent.name,
        discountText = couponEvent.discount.toDisplayText(),
        status = coupon.status(now),
        issuedAt = coupon.createdAt,
        expiresAt = coupon.expiresAt,
        usedAt = coupon.usedAt,
    )

data class UseCouponRequest(
    @field:Positive
    val orderAmount: Int,
)

data class CouponUsageResponse(
    val issuedCouponId: Long,
    val orderAmount: Int,
    val discountAmount: Int,
    val paymentAmount: Int,
)

fun CouponUsage.toResponse(): CouponUsageResponse =
    CouponUsageResponse(
        issuedCouponId = issuedCouponId,
        orderAmount = orderAmount,
        discountAmount = discountAmount,
        paymentAmount = paymentAmount,
    )
