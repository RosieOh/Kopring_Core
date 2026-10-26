package com.site.domain.coupon.service

import com.site.domain.coupon.entity.CouponEvent
import com.site.domain.coupon.entity.CouponEventStatus
import com.site.domain.coupon.entity.CouponIssueMode
import com.site.domain.coupon.entity.EventPeriod
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 발급 요청의 빠른 거절(fast-fail)을 DB 없이 하기 위한 캐시. 즉시 발급과 대기열 줄 서기가 함께 쓴다.
 *
 * - 시작된 이벤트는 수정·삭제가 막혀 있으므로(2단계 규칙) 발급 방식과 기간은 바뀔 일이 없다. 처음 한 번만 DB 에서 읽는다.
 *   시작 전 이벤트는 아직 바뀔 수 있으므로 캐시하지 않는다.
 * - 소진은 한 번 확인되면 되돌아가지 않는다. (발급 취소가 없다) 그래서 소진을 본 뒤로는 DB 를 묻지 않고 바로 거절한다.
 *
 * 서버마다 따로 가지는 캐시다. 다른 서버가 먼저 소진을 봤더라도 이 서버는 스스로 한 번 보기 전까지 모를 뿐이고,
 * 그 사이 요청은 발급 단계(락 안 검사, 원자적 UPDATE)에서 거절되므로 정합성에는 영향이 없다.
 */
@Component
class StartedCouponEventCache(
    private val couponEventRepository: CouponEventRepository,
    private val clock: Clock,
) {

    private val events = ConcurrentHashMap<Long, StartedCouponEvent>()

    /**
     * 지금 [mode] 방식으로 발급을 시도해도 되는지 확인한다. 통과해도 발급이 보장되지는 않는다.
     *
     * 오픈 직후 처음 몇 ms 동안은 캐시가 비어 있어 동시에 들어온 요청이 각자 DB 를 읽을 수 있다.
     * 한 요청만 읽게 막으려면(single-flight) 나머지를 기다리게 해야 하는데, 그 정도 중복은 감수하고 단순하게 둔다.
     */
    fun checkIssuable(couponEventId: Long, mode: CouponIssueMode) {
        val now = LocalDateTime.now(clock)
        val event = events[couponEventId] ?: load(couponEventId, now)

        event.issueMode.checkRequested(mode)
        val status = event.status(now)
        if (status == CouponEventStatus.ENDED) {
            events.remove(couponEventId)
        }
        status.checkIssuable()
    }

    /** 발급 단계에서 소진을 확인했을 때 부른다. 이후 이 이벤트의 요청은 DB 를 거치지 않고 거절된다. */
    fun markSoldOut(couponEventId: Long) {
        events.computeIfPresent(couponEventId) { _, event -> event.copy(soldOut = true) }
    }

    private fun load(couponEventId: Long, now: LocalDateTime): StartedCouponEvent {
        val couponEvent = couponEventRepository.findByIdOrNull(couponEventId)
            ?: throw ServiceException(ErrorCode.COUPON_EVENT_NOT_FOUND)

        val event = StartedCouponEvent.of(couponEvent, now)
        if (!couponEvent.period.isUpcoming(now)) {
            events[couponEventId] = event
        }
        return event
    }

    private data class StartedCouponEvent(
        val issueMode: CouponIssueMode,
        val period: EventPeriod,
        val soldOut: Boolean,
    ) {
        fun status(now: LocalDateTime): CouponEventStatus = CouponEventStatus.of(period, soldOut, now)

        companion object {
            fun of(couponEvent: CouponEvent, now: LocalDateTime) =
                StartedCouponEvent(
                    issueMode = couponEvent.issueMode,
                    period = couponEvent.period,
                    soldOut = couponEvent.status(now) == CouponEventStatus.SOLD_OUT,
                )
        }
    }
}
