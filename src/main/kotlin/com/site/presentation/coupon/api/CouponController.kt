package com.site.presentation.coupon.api

import com.site.domain.coupon.queue.CouponQueueService
import com.site.domain.coupon.service.CouponIssueFacade
import com.site.independent.response.SuccessResponse
import com.site.independent.response.toResponseEntity
import com.site.independent.type.SuccessCode
import com.site.infrastructure.security.AuthMember
import com.site.presentation.coupon.dto.CouponQueueStatusResponse
import com.site.presentation.coupon.dto.IssuedCouponResponse
import com.site.presentation.coupon.dto.toResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/coupon-events")
class CouponController(
    private val couponIssueFacade: CouponIssueFacade,
    private val couponQueueService: CouponQueueService,
) {

    /** 즉시 발급 (분산락 + 사전 검사) */
    @PostMapping("/{couponEventId}/issue")
    fun issue(
        @PathVariable couponEventId: Long,
        @AuthenticationPrincipal authMember: AuthMember,
    ): ResponseEntity<SuccessResponse<IssuedCouponResponse>> {
        val issuedCoupon = couponIssueFacade.issue(couponEventId, authMember.memberId)
        return SuccessCode.ISSUE_COUPON_SUCCESS.toResponseEntity(issuedCoupon.toResponse())
    }

    /** 대기열 등록. 발급은 나중에 워커가 하므로 "접수됨(202)"으로 응답한다. */
    @PostMapping("/{couponEventId}/queue")
    fun enterQueue(
        @PathVariable couponEventId: Long,
        @AuthenticationPrincipal authMember: AuthMember,
    ): ResponseEntity<SuccessResponse<CouponQueueStatusResponse>> {
        val status = couponQueueService.enqueue(couponEventId, authMember.memberId)
        return SuccessCode.ENTER_COUPON_QUEUE_SUCCESS.toResponseEntity(status.toResponse())
    }

    @GetMapping("/{couponEventId}/queue/me")
    fun getQueueStatus(
        @PathVariable couponEventId: Long,
        @AuthenticationPrincipal authMember: AuthMember,
    ): ResponseEntity<SuccessResponse<CouponQueueStatusResponse>> {
        val status = couponQueueService.getStatus(couponEventId, authMember.memberId)
        return SuccessCode.GET_COUPON_QUEUE_STATUS_SUCCESS.toResponseEntity(status.toResponse())
    }
}
