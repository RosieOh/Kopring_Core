package com.site.domain.coupon.queue

import com.site.domain.coupon.queue.port.CouponQueueStore
import com.site.domain.coupon.service.CouponIssueMetrics
import com.site.domain.coupon.service.CouponIssueService
import com.site.domain.coupon.service.QueuedBatchIssueResult
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
 * 3. 앞에서부터 batchSize 명씩 꺼내 한 트랜잭션으로 일괄 발급한다. 줄이 남아 있으면 maxBusyTime 까지 쉬지 않고 이어서 처리한다
 *
 * 같은 회원을 두 번 처리해도 안전하다. 일괄 발급은 이미 받은 회원을 발급된 것으로 돌려준다. (멱등)
 */
@Component
class CouponQueueWorker(
    private val couponQueueStore: CouponQueueStore,
    private val couponIssueService: CouponIssueService,
    private val properties: CouponQueueProperties,
    private val issueMetrics: CouponIssueMetrics,
    private val queueMetrics: CouponQueueMetrics,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun processAll() {
        couponQueueStore.activeEventIds().forEach { couponEventId ->
            runCatching { process(couponEventId) }
                .onFailure { log.error("대기열 처리 중 오류. couponEventId={}", couponEventId, it) }
        }
        queueMetrics.refresh(couponQueueStore.activeEventIds())
    }

    private fun process(couponEventId: Long) {
        val startedAt = clock.millis()

        val expired = couponQueueStore.claimExpired(couponEventId, startedAt, properties.batchSize, leaseUntil())
        if (expired.isNotEmpty()) {
            log.warn("처리 중에 멈춘 회원 {}명을 다시 처리합니다. couponEventId={}", expired.size, couponEventId)
            // 발급까지 끝낸 뒤 죽었을 수도 있지만, 일괄 발급은 이미 받은 회원을 발급된 것으로 돌려주므로 마감 여부와 상관없이 다시 처리한다
            issueBatch(couponEventId, expired)
        }

        while (true) {
            couponQueueStore.closedReason(couponEventId)?.let { reason ->
                drain(couponEventId, reason)
                return
            }

            val claimed = couponQueueStore.claimNext(couponEventId, properties.batchSize, leaseUntil())
            if (claimed.isEmpty()) {
                couponQueueStore.deactivateIfEmpty(couponEventId)
                return
            }
            issueBatch(couponEventId, claimed)

            // 꽉 찬 배치였다면 줄이 더 남아 있을 수 있으니 쉬지 않고 이어서 처리한다 (한 이벤트에 maxBusyTime 까지만)
            if (claimed.size < properties.batchSize || clock.millis() - startedAt >= properties.maxBusyTime.toMillis()) return
        }
    }

    private fun issueBatch(couponEventId: Long, memberIds: List<Long>) {
        val results = try {
            couponIssueService.issueQueuedBatch(couponEventId, memberIds).toQueueResults()
        } catch (e: Exception) {
            log.warn("일괄 발급에 실패해 한 명씩 다시 처리합니다. couponEventId={}, members={}", couponEventId, memberIds.size, e)
            memberIds.associateWith { issueOne(couponEventId, it) }
        }

        results.entries.groupBy({ it.value }, { it.key }).forEach { (result, ids) ->
            couponQueueStore.complete(couponEventId, ids, result)
            issueMetrics.recordQueue(result, count = ids.size.toLong())
        }
        // 소진·종료를 만나면 대기열을 닫아 새로 줄 서는 것을 막는다. 남은 대기자는 다음 반복에서 한꺼번에 정리한다
        results.values.firstOrNull { it.closesQueue }?.let { couponQueueStore.close(couponEventId, it) }
    }

    /** 일괄 발급이 실패했을 때의 대안: 한 명씩 원자적 UPDATE 로 발급한다 */
    private fun issueOne(couponEventId: Long, memberId: Long): CouponQueueResult =
        try {
            couponIssueService.issueWithAtomicUpdate(couponEventId, memberId)
            CouponQueueResult.ISSUED
        } catch (e: ServiceException) {
            e.errorCode.toQueueResult()
        } catch (e: Exception) {
            log.error("대기열 발급 실패. couponEventId={}, memberId={}", couponEventId, memberId, e)
            CouponQueueResult.FAILED
        }

    private fun QueuedBatchIssueResult.toQueueResults(): Map<Long, CouponQueueResult> =
        issued.associateWith { CouponQueueResult.ISSUED } + rejected.associateWith { rejectReason.toQueueResult() }

    private fun ErrorCode.toQueueResult(): CouponQueueResult =
        when (this) {
            ErrorCode.COUPON_SOLD_OUT -> CouponQueueResult.SOLD_OUT
            ErrorCode.COUPON_EVENT_ENDED -> CouponQueueResult.ENDED
            // 대기열 이벤트의 쿠폰은 대기열로만 받을 수 있으므로, 이미 발급됐다면 이전 처리에서 받은 것이다
            ErrorCode.ALREADY_ISSUED_COUPON -> CouponQueueResult.ISSUED
            else -> CouponQueueResult.FAILED
        }

    private fun drain(couponEventId: Long, reason: CouponQueueResult) {
        do {
            val drained = couponQueueStore.drainWaiting(couponEventId, DRAIN_BATCH_SIZE, reason)
            if (drained > 0) issueMetrics.recordQueue(reason, count = drained)
        } while (drained > 0)
        couponQueueStore.deactivateIfEmpty(couponEventId)
    }

    private fun leaseUntil(): Long = clock.millis() + properties.processingLease.toMillis()

    companion object {
        private const val DRAIN_BATCH_SIZE = 1_000L
    }
}
