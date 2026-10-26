package com.site.domain.coupon.service

import com.site.domain.coupon.entity.CouponIssueMode
import com.site.domain.coupon.entity.IssuedCoupon
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import org.springframework.stereotype.Service

/**
 * 즉시 발급의 입구. 빠른 거절(fast-fail) → 설정한 방식으로 발급.
 *
 * 빠른 거절이 없으면 쿠폰이 소진된 뒤에도 나머지 요청이 전부 락(또는 행 잠금) 앞에 줄을 서서 "소진" 응답을 기다린다.
 * 시작 전·종료·소진·방식 불일치처럼 "어차피 실패할 요청"을 메모리 캐시만 보고 먼저 돌려보낸다. (DB 를 읽지 않는다)
 * 이미 받은 회원인지는 여기서 보지 않는다. 회원마다 DB 를 읽어야 하는데, 같은 회원이 다시 요청하는 일은 드물다.
 *
 * 사전 검사를 통과한 요청만 발급 단계의 동시 실행 제한(CouponIssueBulkhead)을 거친다. 소진 뒤 요청은 자리를 기다리지 않는다.
 * 자리를 얻은 뒤에도 한 번 더 확인한다. 줄을 서 있는 사이에 소진되면, 남은 줄이 자리마다 DB 를 한 번씩 거치며 천천히 빠지기 때문이다.
 *
 * 사전 검사는 최적화일 뿐 정합성은 여전히 발급 단계의 검사가 책임진다. (Double-Checked)
 *
 * 왜 CouponIssueService 안에 두지 않았나: 같은 클래스 안에서 issueWithDistributedLock 을 부르면
 * 프록시를 거치지 않아(self-invocation) @DistributedLock 이 동작하지 않는다.
 */
@Service
class CouponIssueFacade(
    private val startedCouponEvents: StartedCouponEventCache,
    private val couponIssueService: CouponIssueService,
    private val bulkhead: CouponIssueBulkhead,
    private val properties: CouponIssueProperties,
    private val metrics: CouponIssueMetrics,
) {

    fun issue(couponEventId: Long, memberId: Long): IssuedCoupon =
        try {
            startedCouponEvents.checkIssuable(couponEventId, CouponIssueMode.DIRECT)
            bulkhead.execute {
                // 자리를 기다리는 사이에 소진됐을 수 있다. 메모리에서 다시 확인해, 소진 뒤 줄은 DB 를 거치지 않고 바로 빠지게 한다
                startedCouponEvents.checkIssuable(couponEventId, CouponIssueMode.DIRECT)
                when (properties.directStrategy) {
                    DirectIssueStrategy.ATOMIC_UPDATE -> couponIssueService.issueWithAtomicUpdate(couponEventId, memberId, CouponIssueMode.DIRECT)
                    DirectIssueStrategy.DISTRIBUTED_LOCK -> couponIssueService.issueWithDistributedLock(couponEventId, memberId)
                }
            }.also { metrics.recordDirectIssued() }
        } catch (e: ServiceException) {
            if (e.errorCode == ErrorCode.COUPON_SOLD_OUT) {
                startedCouponEvents.markSoldOut(couponEventId)
            }
            metrics.recordDirectFailed(e.errorCode)
            throw e
        }
}
