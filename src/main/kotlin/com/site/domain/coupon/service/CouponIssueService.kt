package com.site.domain.coupon.service

import com.site.domain.coupon.entity.CouponEvent
import com.site.domain.coupon.entity.IssuedCoupon
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.coupon.repository.IssuedCouponRepository
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import com.site.infrastructure.redis.lock.DistributedLock
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 선착순 쿠폰 발급.
 *
 * 아래 네 메서드는 하는 일이 같고 "동시성 제어 방식"만 다르다. 같은 동시성 테스트로 비교하기 위한 학습용 구성이며,
 * 실제 API 는 [issueWithDistributedLock] 을 쓴다. 실무라면 하나만 남긴다.
 *
 * 공통 임계 영역: 이벤트 조회 → 발급 가능 여부 확인 → issuedQuantity + 1 → 발급 내역 저장
 *
 * 분산락 메서드 때문에 클래스 레벨 @Transactional 을 두지 않고, 메서드마다 붙였다.
 */
@Service
class CouponIssueService(
    private val couponEventRepository: CouponEventRepository,
    private val issuedCouponRepository: IssuedCouponRepository,
    private val clock: Clock,
) {

    /**
     * [1] 락 없음 — 동시 요청에서 깨진다.
     * 두 요청이 issuedQuantity = 99 를 동시에 읽으면 둘 다 "1장 남았다"고 판단해 쿠폰을 2장 내주고,
     * 각자 100 을 써서 수량은 1 만 늘어난다. (Lost Update)
     */
    @Transactional
    fun issueWithoutLock(couponEventId: Long, memberId: Long): IssuedCoupon {
        val couponEvent = findCouponEvent(couponEventId)
        return issue(couponEvent, memberId)
    }

    /**
     * [2] 비관적 락 — SELECT ... FOR UPDATE 로 이벤트 행을 잠근다.
     * 같은 이벤트에 대한 요청은 DB 에서 한 줄로 서서 하나씩 처리된다.
     * 가장 단순하지만, 기다리는 동안에도 DB 커넥션을 붙잡고 있어서 요청이 몰리면 커넥션 풀이 먼저 바닥난다.
     */
    @Transactional
    fun issueWithPessimisticLock(couponEventId: Long, memberId: Long): IssuedCoupon {
        val couponEvent = couponEventRepository.findByIdForUpdate(couponEventId)
            ?: throw ServiceException(ErrorCode.COUPON_EVENT_NOT_FOUND)
        return issue(couponEvent, memberId)
    }

    /**
     * [3] 분산락 — Redis 락을 먼저 잡고, 그 안에서 트랜잭션을 열어 커밋한 뒤 락을 푼다.
     * 기다리는 건 Redis 에서 하므로 DB 커넥션을 붙잡지 않고, 서버가 여러 대여도 동작한다.
     * 대신 Redis 라는 의존성이 하나 늘고, 락 획득/해제만큼 왕복 시간이 추가된다.
     */
    @DistributedLock(key = "'coupon-event:' + #couponEventId")
    fun issueWithDistributedLock(couponEventId: Long, memberId: Long): IssuedCoupon {
        val couponEvent = findCouponEvent(couponEventId)
        return issue(couponEvent, memberId)
    }

    /**
     * [4] 원자적 조건부 UPDATE — 애플리케이션이 값을 읽고 판단하는 대신,
     * "남아 있으면 +1"을 DB 가 UPDATE 한 문장 안에서 처리한다. 읽은 값과 쓰는 값 사이에 틈이 없다.
     * (UPDATE 도 커밋까지 행 잠금을 쥐지만, 미리 읽는 단계가 없어 잠금 구간이 짧다)
     * 대신 발급 조건이 엔티티(CouponEvent.checkIssuable)와 쿼리 두 곳에 나뉘어 들어간다.
     */
    @Transactional
    fun issueWithAtomicUpdate(couponEventId: Long, memberId: Long): IssuedCoupon {
        checkNotIssued(couponEventId, memberId)

        val now = now()
        if (couponEventRepository.increaseIssuedQuantity(couponEventId, now) == 0) {
            // 실패했을 때만 이유를 알려주기 위해 조회한다. 같은 now 를 쓰므로 UPDATE 가 실패한 이유와 같은 판단이 나온다.
            findCouponEvent(couponEventId).checkIssuable(now)
            throw ServiceException(ErrorCode.COUPON_SOLD_OUT)
        }
        return saveIssuedCoupon(couponEventId, memberId)
    }

    /**
     * 락 없이 가볍게 확인한다. (CouponIssueFacade 의 빠른 거절용)
     * 여기를 통과해도 발급이 보장되지는 않는다. 락 안에서 같은 검사를 다시 한다.
     */
    @Transactional(readOnly = true)
    fun precheck(couponEventId: Long, memberId: Long) {
        findCouponEvent(couponEventId).checkIssuable(now())
        checkNotIssued(couponEventId, memberId)
    }

    private fun issue(couponEvent: CouponEvent, memberId: Long): IssuedCoupon {
        checkNotIssued(couponEvent.id, memberId)
        couponEvent.issue(now())
        return saveIssuedCoupon(couponEvent.id, memberId)
    }

    private fun findCouponEvent(couponEventId: Long): CouponEvent =
        couponEventRepository.findByIdOrNull(couponEventId)
            ?: throw ServiceException(ErrorCode.COUPON_EVENT_NOT_FOUND)

    private fun checkNotIssued(couponEventId: Long, memberId: Long) {
        if (issuedCouponRepository.existsByCouponEventIdAndMemberId(couponEventId, memberId)) {
            throw ServiceException(ErrorCode.ALREADY_ISSUED_COUPON)
        }
    }

    // 같은 회원의 동시 요청이 checkNotIssued 를 함께 통과해도 unique 제약이 막는다.
    // 예외가 나면 트랜잭션 전체가 롤백되므로 올려둔 issuedQuantity 도 함께 되돌아간다.
    private fun saveIssuedCoupon(couponEventId: Long, memberId: Long): IssuedCoupon =
        try {
            issuedCouponRepository.saveAndFlush(IssuedCoupon(couponEventId = couponEventId, memberId = memberId))
        } catch (e: DataIntegrityViolationException) {
            throw ServiceException(ErrorCode.ALREADY_ISSUED_COUPON)
        }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)
}
