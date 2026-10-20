package com.site.presentation.coupon.api

import com.site.domain.coupon.service.CouponIssueService
import com.site.independent.response.SuccessResponse
import com.site.independent.response.toResponseEntity
import com.site.independent.type.SuccessCode
import com.site.infrastructure.security.AuthMember
import com.site.presentation.coupon.dto.IssuedCouponResponse
import com.site.presentation.coupon.dto.toResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/coupon-events")
class CouponController(
    private val couponIssueService: CouponIssueService,
) {

    @PostMapping("/{couponEventId}/issue")
    fun issue(
        @PathVariable couponEventId: Long,
        @AuthenticationPrincipal authMember: AuthMember,
    ): ResponseEntity<SuccessResponse<IssuedCouponResponse>> {
        val issuedCoupon = couponIssueService.issueWithDistributedLock(couponEventId, authMember.memberId)
        return SuccessCode.ISSUE_COUPON_SUCCESS.toResponseEntity(issuedCoupon.toResponse())
    }
}
