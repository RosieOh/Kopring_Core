package com.site.domain.coupon.service

import com.site.domain.coupon.entity.CouponEvent
import com.site.domain.coupon.entity.CouponIssueMode
import com.site.domain.coupon.entity.IssuedCoupon
import com.site.domain.coupon.event.CouponIssuedEvent
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.coupon.repository.IssuedCouponRepository
import com.site.domain.notification.service.NotificationOutboxService
import com.site.independent.error.exception.ServiceException
import com.site.independent.lock.DistributedLock
import com.site.independent.type.ErrorCode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 선착순 쿠폰 발급.
 *
 * 발급 방식(CouponIssueMode)마다 동시성 제어 방식이 다르다.
 * - 즉시 발급(DIRECT): [issueWithDistributedLock] — Redis 분산락
 * - 대기열(QUEUE): [issueWithAtomicUpdate] — 대기열 워커가 원자적 조건부 UPDATE 로 발급
 *
 * 3단계에서 함께 비교했던 "락 없음", "비관적 락" 방식은 운영 코드에서 빼서 테스트 소스
 * (LearningCouponIssueStrategies)로 옮겼다. 운영 코드에 두면 누군가 락 없는 버전을 호출해 초과 발급이 날 수 있다.
 *
 * 공통 임계 영역: 이벤트 조회 → 발급 가능 여부 확인 → issuedQuantity + 1 → 발급 내역 저장
 *
 * 분산락 메서드 때문에 클래스 레벨 @Transactional 을 두지 않고, 메서드마다 붙였다.
 */
@Service
class CouponIssueService(
    private val couponEventRepository: CouponEventRepository,
    private val issuedCouponRepository: IssuedCouponRepository,
    private val notificationOutboxService: NotificationOutboxService,
    private val clock: Clock,
) {

    /**
     * 즉시 발급: 분산락 — Redis 락을 먼저 잡고, 그 안에서 트랜잭션을 열어 커밋한 뒤 락을 푼다.
     * 기다리는 건 Redis 에서 하므로 DB 커넥션을 붙잡지 않고, 서버가 여러 대여도 동작한다.
     * 대신 Redis 라는 의존성이 하나 늘고, 락 획득/해제만큼 왕복 시간이 추가된다.
     */
    @DistributedLock(key = "'coupon-event:' + #couponEventId")
    fun issueWithDistributedLock(couponEventId: Long, memberId: Long): IssuedCoupon {
        val couponEvent = findCouponEvent(couponEventId)
        couponEvent.checkIssueMode(CouponIssueMode.DIRECT)
        return issue(couponEvent, memberId)
    }

    /**
     * 대기열 발급: 원자적 조건부 UPDATE — 애플리케이션이 값을 읽고 판단하는 대신,
     * "남아 있으면 +1"을 DB 가 UPDATE 한 문장 안에서 처리한다. 읽은 값과 쓰는 값 사이에 틈이 없다.
     * (UPDATE 도 커밋까지 행 잠금을 쥐지만, 미리 읽는 단계가 없어 잠금 구간이 짧다)
     * 대신 발급 조건이 엔티티(CouponEvent.checkIssuable)와 쿼리 두 곳에 나뉘어 들어간다.
     *
     * 대기열 워커가 쓰는 방식이다. 발급 방식(QUEUE) 확인은 줄을 세울 때(CouponQueueService) 이미 했다.
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
        // 만료 시각 계산에 유효기간이 필요하다. UPDATE 는 영속성 컨텍스트를 거치지 않으므로 여기서 읽는 값은 최신이다.
        return saveIssuedCoupon(findCouponEvent(couponEventId), memberId)
    }

    /**
     * 락 없이 가볍게 확인한다. (CouponIssueFacade 의 빠른 거절용)
     * 여기를 통과해도 발급이 보장되지는 않는다. 락 안에서 같은 검사를 다시 한다.
     */
    @Transactional(readOnly = true)
    fun precheck(couponEventId: Long, memberId: Long) {
        val couponEvent = findCouponEvent(couponEventId)
        couponEvent.checkIssueMode(CouponIssueMode.DIRECT)
        couponEvent.checkIssuable(now())
        checkNotIssued(couponEventId, memberId)
    }

    private fun issue(couponEvent: CouponEvent, memberId: Long): IssuedCoupon {
        checkNotIssued(couponEvent.id, memberId)
        couponEvent.issue(now())
        return saveIssuedCoupon(couponEvent, memberId)
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
    private fun saveIssuedCoupon(couponEvent: CouponEvent, memberId: Long): IssuedCoupon {
        val couponEventId = couponEvent.id
        val issuedCoupon = try {
            issuedCouponRepository.saveAndFlush(
                IssuedCoupon(couponEventId = couponEventId, memberId = memberId, expiresAt = couponEvent.couponExpiresAt(now())),
            )
        } catch (e: DataIntegrityViolationException) {
            throw ServiceException(ErrorCode.ALREADY_ISSUED_COUPON)
        }

        // 같은 트랜잭션에서 "보낼 알림"을 기록한다. 발급이 롤백되면 알림 기록도 함께 사라진다. (Transactional Outbox)
        notificationOutboxService.recordCouponIssued(
            CouponIssuedEvent(issuedCouponId = issuedCoupon.id, couponEventId = couponEventId, memberId = memberId),
        )
        return issuedCoupon
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)
}
