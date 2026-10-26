package com.site.presentation.coupon.api

import com.site.domain.coupon.queue.CouponQueuePollingPolicy
import com.site.domain.coupon.queue.CouponQueueService
import com.site.domain.coupon.service.CouponEventService
import com.site.domain.coupon.service.CouponIssueFacade
import com.site.independent.response.PageResponse
import com.site.independent.response.SuccessResponse
import com.site.independent.response.toPageResponse
import com.site.independent.response.toResponseEntity
import com.site.independent.type.SuccessCode
import com.site.infrastructure.security.AuthMember
import com.site.presentation.coupon.dto.CouponEventSummaryResponse
import com.site.presentation.coupon.dto.CouponQueueStatusResponse
import com.site.presentation.coupon.dto.IssuedCouponResponse
import com.site.presentation.coupon.dto.toResponse
import com.site.presentation.coupon.dto.toSummaryResponse
import com.site.presentation.coupon.stream.CouponQueueStatusStreams
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Clock
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/coupon-events")
class CouponController(
    private val couponEventService: CouponEventService,
    private val couponIssueFacade: CouponIssueFacade,
    private val couponQueueService: CouponQueueService,
    private val pollingPolicy: CouponQueuePollingPolicy,
    private val queueStreams: CouponQueueStatusStreams,
    private val clock: Clock,
) {

    /** 진행 중·예정 이벤트 (시작이 빠른 순) */
    @GetMapping
    fun getCouponEvents(
        @PageableDefault(size = 20, sort = ["period.startAt"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): ResponseEntity<SuccessResponse<PageResponse<CouponEventSummaryResponse>>> {
        val now = LocalDateTime.now(clock)
        val events = couponEventService.getNotEndedCouponEvents(pageable)
        return SuccessCode.GET_COUPON_EVENTS_SUCCESS.toResponseEntity(events.toPageResponse { it.toSummaryResponse(now) })
    }

    /**
     * 즉시 발급. 사전 검사(메모리 캐시) → 동시 실행 제한 → 설정한 방식(coupon.issue.direct-strategy, 기본 원자적 UPDATE)으로 발급한다.
     * 흐름은 CouponIssueFacade 참고.
     */
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
        return SuccessCode.ENTER_COUPON_QUEUE_SUCCESS.toResponseEntity(status.toResponse(pollingPolicy.retryAfterMillis(couponEventId, status)))
    }

    @GetMapping("/{couponEventId}/queue/me")
    fun getQueueStatus(
        @PathVariable couponEventId: Long,
        @AuthenticationPrincipal authMember: AuthMember,
    ): ResponseEntity<SuccessResponse<CouponQueueStatusResponse>> {
        val status = couponQueueService.getStatus(couponEventId, authMember.memberId)
        return SuccessCode.GET_COUPON_QUEUE_STATUS_SUCCESS.toResponseEntity(status.toResponse(pollingPolicy.retryAfterMillis(couponEventId, status)))
    }

    /**
     * 내 상태를 SSE 로 받는다. 상태가 바뀔 때마다 `event: status` 로 보내고, 결과가 나오면 연결을 닫는다.
     * 브라우저 기본 EventSource 는 Authorization 헤더를 못 붙이므로 fetch 기반 SSE 클라이언트로 연결한다.
     */
    @GetMapping("/{couponEventId}/queue/me/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamQueueStatus(@PathVariable couponEventId: Long, @AuthenticationPrincipal authMember: AuthMember): SseEmitter =
        queueStreams.subscribe(couponEventId, authMember.memberId)
}
