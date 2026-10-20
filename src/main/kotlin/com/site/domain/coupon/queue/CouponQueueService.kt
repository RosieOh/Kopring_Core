package com.site.domain.coupon.queue

import com.site.domain.coupon.entity.EventPeriod
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import com.site.infrastructure.redis.queue.CouponQueueRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 선착순 대기열. 요청 스레드는 Redis 에 줄만 세우고 바로 응답한다. 실제 발급(DB)은 CouponQueueWorker 가 순서대로 처리한다.
 *
 * 대기열은 트래픽을 완충할 뿐이고, "100장 초과 발급 금지" 같은 정합성은 여전히 DB(원자적 UPDATE)가 책임진다.
 */
@Service
class CouponQueueService(
    private val couponQueueRepository: CouponQueueRepository,
    private val couponEventRepository: CouponEventRepository,
    private val clock: Clock,
) {

    /**
     * 진행 중인 이벤트의 기간을 메모리에 캐시한다. 줄 서기 요청마다 DB 를 읽지 않기 위해서다.
     * 시작된 이벤트는 수정·삭제가 막혀 있으므로(2단계 규칙) 캐시한 값이 바뀔 일이 없다.
     */
    private val ongoingPeriods = ConcurrentHashMap<Long, EventPeriod>()

    fun enqueue(couponEventId: Long, memberId: Long): CouponQueueStatus {
        checkOngoing(couponEventId)

        return when (val rank = couponQueueRepository.enqueue(couponEventId, memberId)) {
            CouponQueueRepository.ALREADY_PROCESSED -> getStatus(couponEventId, memberId)
            CouponQueueRepository.CLOSED -> throw ServiceException(closedErrorCode(couponEventId))
            else -> {
                couponQueueRepository.activate(couponEventId)
                CouponQueueStatus.Waiting(position = rank + 1)
            }
        }
    }

    fun getStatus(couponEventId: Long, memberId: Long): CouponQueueStatus {
        couponQueueRepository.findResult(couponEventId, memberId)
            ?.let { return CouponQueueStatus.Done(it) }

        return couponQueueRepository.rank(couponEventId, memberId)
            ?.let { CouponQueueStatus.Waiting(position = it + 1) }
            ?: CouponQueueStatus.NotInQueue
    }

    private fun checkOngoing(couponEventId: Long) {
        val now = LocalDateTime.now(clock)

        ongoingPeriods[couponEventId]?.let { period ->
            if (period.isEnded(now)) {
                ongoingPeriods.remove(couponEventId)
                throw ServiceException(ErrorCode.COUPON_EVENT_ENDED)
            }
            return
        }

        val couponEvent = couponEventRepository.findByIdOrNull(couponEventId)
            ?: throw ServiceException(ErrorCode.COUPON_EVENT_NOT_FOUND)
        couponEvent.checkIssuable(now)
        ongoingPeriods[couponEventId] = couponEvent.period
    }

    private fun closedErrorCode(couponEventId: Long): ErrorCode =
        when (couponQueueRepository.closedReason(couponEventId)) {
            CouponQueueResult.ENDED -> ErrorCode.COUPON_EVENT_ENDED
            else -> ErrorCode.COUPON_SOLD_OUT
        }
}
