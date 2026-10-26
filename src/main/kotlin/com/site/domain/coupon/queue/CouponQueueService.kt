package com.site.domain.coupon.queue

import com.site.domain.coupon.entity.CouponIssueMode
import com.site.domain.coupon.queue.port.CouponQueueStore
import com.site.domain.coupon.queue.port.EnqueueResult
import com.site.domain.coupon.service.StartedCouponEventCache
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import org.springframework.stereotype.Service

/**
 * 선착순 대기열. 요청 스레드는 Redis 에 줄만 세우고 바로 응답한다. 실제 발급(DB)은 CouponQueueWorker 가 순서대로 처리한다.
 *
 * 대기열은 트래픽을 완충할 뿐이고, "100장 초과 발급 금지" 같은 정합성은 여전히 DB(원자적 UPDATE)가 책임진다.
 */
@Service
class CouponQueueService(
    private val couponQueueStore: CouponQueueStore,
    private val startedCouponEvents: StartedCouponEventCache,
) {

    fun enqueue(couponEventId: Long, memberId: Long): CouponQueueStatus {
        // 줄 서기 요청마다 DB 를 읽지 않도록 메모리 캐시로 확인한다
        startedCouponEvents.checkIssuable(couponEventId, CouponIssueMode.QUEUE)

        return when (val result = couponQueueStore.enqueue(couponEventId, memberId)) {
            is EnqueueResult.Enqueued -> {
                couponQueueStore.activate(couponEventId)
                CouponQueueStatus.Waiting(position = result.rank + 1)
            }
            EnqueueResult.AlreadyProcessed -> getStatus(couponEventId, memberId)
            EnqueueResult.Closed -> throw ServiceException(closedErrorCode(couponEventId))
        }
    }

    fun getStatus(couponEventId: Long, memberId: Long): CouponQueueStatus {
        couponQueueStore.findResult(couponEventId, memberId)
            ?.let { return CouponQueueStatus.Done(it) }

        couponQueueStore.rank(couponEventId, memberId)
            ?.let { return CouponQueueStatus.Waiting(position = it + 1) }

        return if (couponQueueStore.isProcessing(couponEventId, memberId)) CouponQueueStatus.Processing else CouponQueueStatus.NotInQueue
    }

    private fun closedErrorCode(couponEventId: Long): ErrorCode =
        when (couponQueueStore.closedReason(couponEventId)) {
            CouponQueueResult.ENDED -> ErrorCode.COUPON_EVENT_ENDED
            else -> ErrorCode.COUPON_SOLD_OUT
        }
}
