package com.site.domain.coupon.service

import com.site.domain.coupon.entity.CouponEvent
import com.site.domain.coupon.entity.CouponEventStatus
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
 * 동시성 제어 방식
 * - 즉시 발급(DIRECT): [issueWithAtomicUpdate] 또는 [issueWithDistributedLock] — 설정(coupon.issue.direct-strategy)으로 고른다
 * - 대기열(QUEUE): [issueQueuedBatch] — 워커가 꺼낸 회원을 행 잠금 하나로 한꺼번에 발급. 실패하면 한 명씩 [issueWithAtomicUpdate]
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
     * 원자적 조건부 UPDATE — 애플리케이션이 값을 읽고 판단하는 대신,
     * "남아 있으면 +1"을 DB 가 UPDATE 한 문장 안에서 처리한다. 읽은 값과 쓰는 값 사이에 틈이 없다.
     * 대신 발급 조건이 엔티티(CouponEvent.checkIssuable)와 쿼리 두 곳에 나뉘어 들어간다.
     *
     * UPDATE 는 커밋까지 이벤트 행 잠금을 쥔다. 모든 발급이 이 한 행에 줄을 서므로, 잠근 뒤 커밋까지 할 일을 줄였다.
     * - 이벤트 조회와 중복 발급 확인은 잠그기 전에 한다. 기간·방식·유효기간은 시작 후 바뀌지 않아 미리 읽어도 된다.
     * - 발급 내역 INSERT 를 UPDATE 보다 먼저 하면 잠금 구간이 더 줄지만, 같은 회원의 요청이 셋 이상 겹친 채 앞 요청이 롤백되면
     *   unique 키 잠금끼리 데드락이 날 수 있다. UPDATE 를 먼저 해서 같은 이벤트의 INSERT 가 항상 차례로 일어나게 했다.
     */
    @Transactional
    fun issueWithAtomicUpdate(
        couponEventId: Long,
        memberId: Long,
        mode: CouponIssueMode,
    ): IssuedCoupon {
        val now = now()
        val couponEvent = findCouponEvent(couponEventId)
        couponEvent.checkIssueMode(mode)
        // 여기서 읽은 수량은 곧 낡지만, "이미 소진"이라면 되돌아가지 않으므로 바로 거절해도 된다
        couponEvent.checkIssuable(now)
        checkNotIssued(couponEventId, memberId)

        if (couponEventRepository.increaseIssuedQuantity(couponEventId, mode, now) == 0) {
            // 방식·기간은 같은 now 로 위에서 확인했으므로 남은 이유는 소진뿐이다
            throw ServiceException(ErrorCode.COUPON_SOLD_OUT)
        }
        // UPDATE 는 영속성 컨텍스트를 거치지 않으므로 couponEvent 의 수량은 낡은 값이다. 유효기간만 쓴다
        return saveIssuedCoupon(couponEvent, memberId)
    }

    /**
     * 대기열 워커 전용: 꺼낸 회원들을 한 트랜잭션에서 한꺼번에 발급한다.
     *
     * 이벤트 행을 잠그고(SELECT ... FOR UPDATE) 남은 수량만큼 앞사람부터 나눠 준다. 회원마다 트랜잭션을 열면
     * 커밋(디스크 기록)이 회원 수만큼 일어나지만, 여기서는 배치당 한 번이다.
     * 이미 받은 회원(이전 처리 도중 워커가 죽은 경우)은 다시 발급하지 않고 발급된 것으로 돌려준다. (다시 처리해도 안전하다)
     * 행 잠금 덕분에 워커가 여러 대여도 수량을 넘지 않는다.
     */
    @Transactional
    fun issueQueuedBatch(couponEventId: Long, memberIds: List<Long>): QueuedBatchIssueResult {
        val now = now()
        val couponEvent = couponEventRepository.findByIdForUpdate(couponEventId)
            ?: throw ServiceException(ErrorCode.COUPON_EVENT_NOT_FOUND)
        couponEvent.checkIssueMode(CouponIssueMode.QUEUE)

        val alreadyIssued = issuedCouponRepository.findIssuedMemberIds(couponEventId, memberIds).toSet()
        val candidates = memberIds.filterNot { it in alreadyIssued }
        val status = couponEvent.status(now)
        val winners = if (status == CouponEventStatus.ONGOING) candidates.take(couponEvent.remainingQuantity) else emptyList()

        if (winners.isNotEmpty()) {
            couponEvent.issue(now, quantity = winners.size)
            val expiresAt = couponEvent.couponExpiresAt(now)
            issuedCouponRepository.saveAll(winners.map { IssuedCoupon(couponEventId = couponEventId, memberId = it, expiresAt = expiresAt) })
                .forEach { notificationOutboxService.recordCouponIssued(CouponIssuedEvent(it.id, couponEventId, it.memberId)) }
        }

        val issued = alreadyIssued + winners
        return QueuedBatchIssueResult(
            issued = memberIds.filter { it in issued },
            rejected = candidates.drop(winners.size),
            rejectReason = when (status) {
                CouponEventStatus.ENDED -> ErrorCode.COUPON_EVENT_ENDED
                CouponEventStatus.UPCOMING -> ErrorCode.COUPON_EVENT_NOT_STARTED
                else -> ErrorCode.COUPON_SOLD_OUT
            },
            remainingQuantity = couponEvent.remainingQuantity,
        )
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

/**
 * 일괄 발급 결과. 꺼낸 회원은 issued 나 rejected 중 한쪽에 들어간다.
 * @property issued 이번에 발급했거나 이미 받아 둔 회원 (꺼낸 순서 유지)
 * @property rejected 발급하지 못한 회원. 이유는 rejectReason (소진 / 종료)
 * @property remainingQuantity 이번 배치를 발급한 뒤 남은 수량
 */
data class QueuedBatchIssueResult(
    val issued: List<Long>,
    val rejected: List<Long>,
    val rejectReason: ErrorCode,
    val remainingQuantity: Int,
)
