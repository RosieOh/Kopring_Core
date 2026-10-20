package com.site.presentation.admin.api

import com.site.domain.coupon.service.CouponEventService
import com.site.independent.response.PageResponse
import com.site.independent.response.SuccessResponse
import com.site.independent.response.toPageResponse
import com.site.independent.response.toResponseEntity
import com.site.independent.type.SuccessCode
import com.site.presentation.admin.dto.CouponEventRequest
import com.site.presentation.admin.dto.CouponEventResponse
import com.site.presentation.admin.dto.toCommand
import com.site.presentation.admin.dto.toResponse
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDateTime

/** /api/admin/ 하위는 SecurityConfig 에서 ADMIN 권한만 통과시킨다. */
@RestController
@RequestMapping("/api/admin/coupon-events")
class AdminCouponEventController(
    private val couponEventService: CouponEventService,
    private val clock: Clock,
) {

    @PostMapping
    fun create(@Valid @RequestBody request: CouponEventRequest): ResponseEntity<SuccessResponse<CouponEventResponse>> {
        val couponEvent = couponEventService.create(request.toCommand())
        return SuccessCode.CREATE_COUPON_EVENT_SUCCESS.toResponseEntity(couponEvent.toResponse(now()))
    }

    @GetMapping
    fun getCouponEvents(
        @PageableDefault(size = 20, sort = ["id"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): ResponseEntity<SuccessResponse<PageResponse<CouponEventResponse>>> {
        val now = now()
        val page = couponEventService.getCouponEvents(pageable)
        return SuccessCode.GET_COUPON_EVENTS_SUCCESS.toResponseEntity(page.toPageResponse { it.toResponse(now) })
    }

    @GetMapping("/{couponEventId}")
    fun getCouponEvent(@PathVariable couponEventId: Long): ResponseEntity<SuccessResponse<CouponEventResponse>> {
        val couponEvent = couponEventService.getCouponEvent(couponEventId)
        return SuccessCode.GET_COUPON_EVENT_SUCCESS.toResponseEntity(couponEvent.toResponse(now()))
    }

    @PutMapping("/{couponEventId}")
    fun update(
        @PathVariable couponEventId: Long,
        @Valid @RequestBody request: CouponEventRequest,
    ): ResponseEntity<SuccessResponse<CouponEventResponse>> {
        val couponEvent = couponEventService.update(couponEventId, request.toCommand())
        return SuccessCode.UPDATE_COUPON_EVENT_SUCCESS.toResponseEntity(couponEvent.toResponse(now()))
    }

    @DeleteMapping("/{couponEventId}")
    fun delete(@PathVariable couponEventId: Long): ResponseEntity<SuccessResponse<Nothing?>> {
        couponEventService.delete(couponEventId)
        return SuccessCode.DELETE_COUPON_EVENT_SUCCESS.toResponseEntity()
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)
}
