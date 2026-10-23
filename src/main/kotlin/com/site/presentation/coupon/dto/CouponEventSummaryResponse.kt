package com.site.presentation.coupon.dto

import com.site.domain.coupon.entity.CouponEvent
import com.site.domain.coupon.entity.CouponEventStatus
import com.site.domain.coupon.entity.CouponIssueMode
import java.time.LocalDateTime

/** 사용자에게 보여주는 이벤트 정보. 관리자용 응답과 달리 발급 수량 같은 운영 정보는 빼고 남은 수량만 보여준다. */
data class CouponEventSummaryResponse(
    val id: Long,
    val name: String,
    val discountText: String,
    val remainingQuantity: Int,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
    val status: CouponEventStatus,
    val issueMode: CouponIssueMode,
    val couponValidityDays: Int,
)

fun CouponEvent.toSummaryResponse(now: LocalDateTime): CouponEventSummaryResponse =
    CouponEventSummaryResponse(
        id = id,
        name = name,
        discountText = discount.toDisplayText(),
        remainingQuantity = remainingQuantity,
        startAt = period.startAt,
        endAt = period.endAt,
        status = status(now),
        issueMode = issueMode,
        couponValidityDays = couponValidityDays,
    )
