package com.site.domain.coupon.service

import com.site.domain.coupon.entity.IssuedCoupon
import org.springframework.stereotype.Service

/**
 * 분산락 앞단의 빠른 거절(fast-fail).
 *
 * 분산락만 쓰면 쿠폰이 소진된 뒤에도 나머지 요청이 전부 락 앞에 줄을 서서 "소진" 응답을 받으려고 기다린다.
 * 락을 잡기 전에 소진·종료·시작 전·이미 발급 같은 "어차피 실패할 요청"을 가벼운 조회로 먼저 돌려보낸다.
 *
 * 사전 검사는 최적화일 뿐 정합성은 여전히 락 안의 검사가 책임진다. (Double-Checked)
 *
 * 왜 CouponIssueService 안에 두지 않았나: 같은 클래스 안에서 issueWithDistributedLock 을 부르면
 * 프록시를 거치지 않아(self-invocation) @DistributedLock 이 동작하지 않는다.
 */
@Service
class CouponIssueFacade(
    private val couponIssueService: CouponIssueService,
) {

    fun issue(couponEventId: Long, memberId: Long): IssuedCoupon {
        couponIssueService.precheck(couponEventId, memberId)
        return couponIssueService.issueWithDistributedLock(couponEventId, memberId)
    }
}
