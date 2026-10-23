package com.site.presentation.coupon.api

import com.site.domain.coupon.service.CouponWalletService
import com.site.independent.response.PageResponse
import com.site.independent.response.SuccessResponse
import com.site.independent.response.toPageResponse
import com.site.independent.response.toResponseEntity
import com.site.independent.type.SuccessCode
import com.site.infrastructure.security.AuthMember
import com.site.presentation.coupon.dto.CouponUsageResponse
import com.site.presentation.coupon.dto.MyCouponResponse
import com.site.presentation.coupon.dto.UseCouponRequest
import com.site.presentation.coupon.dto.toResponse
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/members/me/coupons")
class MyCouponController(
    private val couponWalletService: CouponWalletService,
    private val clock: Clock,
) {

    @GetMapping
    fun getMyCoupons(
        @AuthenticationPrincipal authMember: AuthMember,
        @PageableDefault(size = 20, sort = ["id"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): ResponseEntity<SuccessResponse<PageResponse<MyCouponResponse>>> {
        val now = LocalDateTime.now(clock)
        val coupons = couponWalletService.getMyCoupons(authMember.memberId, pageable)
        return SuccessCode.GET_MY_COUPONS_SUCCESS.toResponseEntity(coupons.toPageResponse { it.toResponse(now) })
    }

    @PostMapping("/{issuedCouponId}/use")
    fun use(
        @AuthenticationPrincipal authMember: AuthMember,
        @PathVariable issuedCouponId: Long,
        @Valid @RequestBody request: UseCouponRequest,
    ): ResponseEntity<SuccessResponse<CouponUsageResponse>> {
        val usage = couponWalletService.use(authMember.memberId, issuedCouponId, request.orderAmount)
        return SuccessCode.USE_COUPON_SUCCESS.toResponseEntity(usage.toResponse())
    }
}
