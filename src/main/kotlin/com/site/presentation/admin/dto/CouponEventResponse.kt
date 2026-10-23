package com.site.presentation.admin.dto

import com.site.domain.coupon.entity.CouponEvent
import com.site.domain.coupon.entity.CouponEventStatus
import com.site.domain.coupon.entity.CouponIssueMode
import com.site.domain.coupon.entity.DiscountType
import java.time.LocalDateTime

data class CouponEventResponse(
    val id: Long,
    val name: String,
    val discountType: DiscountType,
    val discountValue: Int,
    val totalQuantity: Int,
    val issuedQuantity: Int,
    val remainingQuantity: Int,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
    val status: CouponEventStatus,
    val issueMode: CouponIssueMode,
    val couponValidityDays: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

// 상태는 시각에 따라 달라지므로 "언제 기준으로" 볼지를 받는다
fun CouponEvent.toResponse(now: LocalDateTime): CouponEventResponse =
    CouponEventResponse(
        id = id,
        name = name,
        discountType = discount.type,
        discountValue = discount.value,
        totalQuantity = totalQuantity,
        issuedQuantity = issuedQuantity,
        remainingQuantity = remainingQuantity,
        startAt = period.startAt,
        endAt = period.endAt,
        status = status(now),
        issueMode = issueMode,
        couponValidityDays = couponValidityDays,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
