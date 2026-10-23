package com.site.presentation.admin.dto

import com.site.domain.coupon.entity.CouponIssueMode
import com.site.domain.coupon.entity.DiscountType
import com.site.domain.coupon.service.CouponEventCommand
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * 등록·수정 공용 요청. (수정은 PUT — 전체 필드를 다시 보낸다)
 *
 * 단일 필드 규칙(빈 값, 양수, 길이)은 여기서, 필드 간 규칙(시작 < 종료, 정률은 100% 이하)은
 * 도메인 값 객체(EventPeriod, Discount)가 검사한다.
 */
data class CouponEventRequest(
    @field:NotBlank
    @field:Size(max = 50)
    val name: String,

    val discountType: DiscountType,

    @field:Positive
    val discountValue: Int,

    @field:Positive
    @field:Max(1_000_000)
    val totalQuantity: Int,

    val startAt: LocalDateTime,

    val endAt: LocalDateTime,

    // 생략하면 즉시 발급. 시작 후에는 바꿀 수 없다.
    val issueMode: CouponIssueMode = CouponIssueMode.DIRECT,
)

fun CouponEventRequest.toCommand(): CouponEventCommand =
    CouponEventCommand(
        name = name,
        discountType = discountType,
        discountValue = discountValue,
        totalQuantity = totalQuantity,
        startAt = startAt,
        endAt = endAt,
        issueMode = issueMode,
    )
