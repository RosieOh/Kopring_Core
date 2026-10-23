package com.site.support

import com.site.domain.coupon.entity.CouponEvent
import com.site.domain.coupon.entity.IssuedCoupon
import com.site.domain.coupon.repository.IssuedCouponRepository
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.boot.test.context.TestComponent
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 동시성 비교 테스트 전용. 운영 코드에서 뺀 "락 없음"과 "비관적 락" 방식을 재현한다. (3단계 학습용)
 *
 * 운영 코드(CouponIssueService)에 두면 누군가 락 없는 버전을 호출해 초과 발급이 날 수 있어서 테스트 소스로 옮겼다.
 * 비교의 공정성을 위해 임계 영역(조회 → 확인 → 수량 +1 → 저장)은 운영 코드와 같게 둔다.
 */
@TestComponent
class LearningCouponIssueStrategies(
    private val entityManager: EntityManager,
    private val issuedCouponRepository: IssuedCouponRepository,
    private val clock: Clock,
) {

    /**
     * 락 없음 — 동시 요청에서 깨진다.
     * 두 요청이 issuedQuantity = 99 를 동시에 읽으면 둘 다 "1장 남았다"고 판단해 쿠폰을 2장 내주고,
     * 각자 100 을 써서 수량은 1 만 늘어난다. (Lost Update)
     */
    @Transactional
    fun issueWithoutLock(couponEventId: Long, memberId: Long): IssuedCoupon = issue(find(couponEventId, LockModeType.NONE), memberId)

    /**
     * 비관적 락 — SELECT ... FOR UPDATE 로 이벤트 행을 잠근다.
     * 같은 이벤트에 대한 요청은 DB 에서 한 줄로 서서 하나씩 처리된다.
     * 가장 단순하지만, 기다리는 동안에도 DB 커넥션을 붙잡고 있어서 요청이 몰리면 커넥션 풀이 먼저 바닥난다.
     */
    @Transactional
    fun issueWithPessimisticLock(couponEventId: Long, memberId: Long): IssuedCoupon =
        issue(find(couponEventId, LockModeType.PESSIMISTIC_WRITE), memberId)

    private fun find(couponEventId: Long, lockMode: LockModeType): CouponEvent =
        entityManager.find(CouponEvent::class.java, couponEventId, lockMode)
            ?: throw ServiceException(ErrorCode.COUPON_EVENT_NOT_FOUND)

    private fun issue(couponEvent: CouponEvent, memberId: Long): IssuedCoupon {
        if (issuedCouponRepository.existsByCouponEventIdAndMemberId(couponEvent.id, memberId)) {
            throw ServiceException(ErrorCode.ALREADY_ISSUED_COUPON)
        }
        val now = LocalDateTime.now(clock)
        couponEvent.issue(now)
        return issuedCouponRepository.saveAndFlush(
            IssuedCoupon(couponEventId = couponEvent.id, memberId = memberId, expiresAt = couponEvent.couponExpiresAt(now)),
        )
    }
}
