package com.site.domain.coupon.queue

import com.site.domain.coupon.service.CouponIssueService
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import com.site.infrastructure.redis.queue.CouponQueueRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 대기열에서 앞사람부터 꺼내 실제로 발급한다. CouponQueueScheduler 가 주기적으로 부른다. (테스트에서는 직접 부른다)
 *
 * 발급은 원자적 UPDATE 방식을 쓴다. ZPOPMIN 덕분에 서버가 여러 대여도 한 회원을 두 번 처리하지 않고,
 * 원자적 UPDATE 덕분에 여러 워커가 동시에 발급해도 수량을 넘지 않는다.
 * (단, 워커가 여러 대면 배치끼리 동시에 처리되어 순번이 조금 뒤섞일 수 있다. 엄격한 순서가 필요하면 워커를 하나로 제한한다)
 */
@Component
class CouponQueueWorker(
    private val couponQueueRepository: CouponQueueRepository,
    private val couponIssueService: CouponIssueService,
    private val properties: CouponQueueProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun processAll() {
        couponQueueRepository.activeEventIds().forEach { couponEventId ->
            runCatching { process(couponEventId) }
                .onFailure { log.error("대기열 처리 중 오류. couponEventId={}", couponEventId, it) }
        }
    }

    private fun process(couponEventId: Long) {
        couponQueueRepository.closedReason(couponEventId)?.let { reason ->
            drain(couponEventId, reason)
            return
        }

        val memberIds = couponQueueRepository.popWaiting(couponEventId, properties.batchSize)
        if (memberIds.isEmpty()) {
            couponQueueRepository.deactivateIfEmpty(couponEventId)
            return
        }

        for ((index, memberId) in memberIds.withIndex()) {
            val result = issue(couponEventId, memberId)
            couponQueueRepository.saveResults(couponEventId, listOf(memberId), result)

            if (result.closesQueue) {
                // 대기열을 먼저 닫아 새로 줄 서는 것을 막은 뒤, 이번 배치의 남은 인원과 대기열 나머지를 같은 결과로 정리한다
                couponQueueRepository.close(couponEventId, result)
                couponQueueRepository.saveResults(couponEventId, memberIds.drop(index + 1), result)
                drain(couponEventId, result)
                return
            }
        }
    }

    private fun issue(couponEventId: Long, memberId: Long): CouponQueueResult =
        try {
            couponIssueService.issueWithAtomicUpdate(couponEventId, memberId)
            CouponQueueResult.ISSUED
        } catch (e: ServiceException) {
            when (e.errorCode) {
                ErrorCode.COUPON_SOLD_OUT -> CouponQueueResult.SOLD_OUT
                ErrorCode.COUPON_EVENT_ENDED -> CouponQueueResult.ENDED
                ErrorCode.ALREADY_ISSUED_COUPON -> CouponQueueResult.ALREADY_ISSUED
                else -> CouponQueueResult.FAILED
            }
        } catch (e: Exception) {
            log.error("대기열 발급 실패. couponEventId={}, memberId={}", couponEventId, memberId, e)
            CouponQueueResult.FAILED
        }

    private fun drain(couponEventId: Long, reason: CouponQueueResult) {
        while (true) {
            val memberIds = couponQueueRepository.popWaiting(couponEventId, DRAIN_BATCH_SIZE)
            if (memberIds.isEmpty()) break
            couponQueueRepository.saveResults(couponEventId, memberIds, reason)
        }
        couponQueueRepository.deactivate(couponEventId)
    }

    companion object {
        private const val DRAIN_BATCH_SIZE = 1_000L
    }
}
