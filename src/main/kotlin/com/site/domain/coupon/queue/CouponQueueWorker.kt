package com.site.domain.coupon.queue

import com.site.domain.coupon.queue.port.CouponQueueStore
import com.site.domain.coupon.service.CouponIssueService
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 대기열에서 앞사람부터 꺼내 실제로 발급한다. CouponQueueScheduler 가 주기적으로 부른다. (테스트에서는 직접 부른다)
 *
 * 한 주기에 하는 일
 * 1. lease 가 지난 "처리 중" 회원을 다시 처리한다 — 이전 워커가 꺼내 놓고 죽은 경우
 * 2. 대기열이 닫혔으면 대기 중인 회원을 DB 를 거치지 않고 같은 결과로 정리한다
 * 3. 앞에서부터 새로 꺼내 발급한다
 *
 * 같은 회원을 두 번 처리해도 안전하다. 발급은 1인 1매 제약이 있는 원자적 UPDATE 로 하므로,
 * 이미 발급된 회원은 "이미 발급됨"이 되고 이를 ISSUED 로 기록한다. (멱등)
 */
@Component
class CouponQueueWorker(
    private val couponQueueStore: CouponQueueStore,
    private val couponIssueService: CouponIssueService,
    private val properties: CouponQueueProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun processAll() {
        couponQueueStore.activeEventIds().forEach { couponEventId ->
            runCatching { process(couponEventId) }
                .onFailure { log.error("대기열 처리 중 오류. couponEventId={}", couponEventId, it) }
        }
    }

    private fun process(couponEventId: Long) {
        val now = clock.millis()
        val leaseUntil = now + properties.processingLease.toMillis()

        val expired = couponQueueStore.claimExpired(couponEventId, now, properties.batchSize, leaseUntil)
        if (expired.isNotEmpty()) {
            log.warn("처리 중에 멈춘 회원 {}명을 다시 처리합니다. couponEventId={}", expired.size, couponEventId)
            // 이미 발급까지 끝난 뒤 죽었을 수도 있으므로, 마감 여부와 상관없이 한 명씩 다시 발급을 시도해 결과를 확정한다
            expired.forEach { memberId -> issueAndComplete(couponEventId, memberId) }
        }

        couponQueueStore.closedReason(couponEventId)?.let { reason ->
            drain(couponEventId, reason)
            return
        }

        val claimed = couponQueueStore.claimNext(couponEventId, properties.batchSize, leaseUntil)
        if (claimed.isEmpty() && expired.isEmpty()) {
            couponQueueStore.deactivateIfEmpty(couponEventId)
            return
        }

        for ((index, memberId) in claimed.withIndex()) {
            val result = issueAndComplete(couponEventId, memberId)
            if (result.closesQueue) {
                // 새로 꺼낸 회원들은 발급 시도 전이라 같은 결과로 한 번에 정리해도 된다
                couponQueueStore.complete(couponEventId, claimed.drop(index + 1), result)
                drain(couponEventId, result)
                return
            }
        }
    }

    private fun issueAndComplete(couponEventId: Long, memberId: Long): CouponQueueResult {
        val result = issue(couponEventId, memberId)
        couponQueueStore.complete(couponEventId, listOf(memberId), result)
        if (result.closesQueue) {
            // 대기열을 먼저 닫아 새로 줄 서는 것을 막는다
            couponQueueStore.close(couponEventId, result)
        }
        return result
    }

    private fun issue(couponEventId: Long, memberId: Long): CouponQueueResult =
        try {
            couponIssueService.issueWithAtomicUpdate(couponEventId, memberId)
            CouponQueueResult.ISSUED
        } catch (e: ServiceException) {
            when (e.errorCode) {
                ErrorCode.COUPON_SOLD_OUT -> CouponQueueResult.SOLD_OUT
                ErrorCode.COUPON_EVENT_ENDED -> CouponQueueResult.ENDED
                // 대기열 이벤트의 쿠폰은 대기열로만 받을 수 있으므로, 이미 발급됐다면 이전 처리에서 받은 것이다
                ErrorCode.ALREADY_ISSUED_COUPON -> CouponQueueResult.ISSUED
                else -> CouponQueueResult.FAILED
            }
        } catch (e: Exception) {
            log.error("대기열 발급 실패. couponEventId={}, memberId={}", couponEventId, memberId, e)
            CouponQueueResult.FAILED
        }

    private fun drain(couponEventId: Long, reason: CouponQueueResult) {
        while (couponQueueStore.drainWaiting(couponEventId, DRAIN_BATCH_SIZE, reason) > 0) {
            // 대기 중인 회원이 없을 때까지 반복
        }
        couponQueueStore.deactivateIfEmpty(couponEventId)
    }

    companion object {
        private const val DRAIN_BATCH_SIZE = 1_000L
    }
}
