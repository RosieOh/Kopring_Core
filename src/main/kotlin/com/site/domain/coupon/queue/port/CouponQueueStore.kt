package com.site.domain.coupon.queue.port

import com.site.domain.coupon.queue.CouponQueueResult

/**
 * 선착순 대기열 저장소 포트. 어떤 연산이 "원자적"이어야 하는지가 이 인터페이스의 계약이다.
 */
interface CouponQueueStore {
    /** 결과 확인 → 마감 확인 → 중복 확인 → 순번 발급 → 추가를 원자적으로 한다. */
    fun enqueue(couponEventId: Long, memberId: Long): EnqueueResult

    fun activate(couponEventId: Long)

    fun activeEventIds(): Set<Long>

    fun deactivate(couponEventId: Long)

    /** 비어 있으면 순회 대상에서 뺀다. 빼는 사이에 누가 들어왔다면 다시 넣는다. */
    fun deactivateIfEmpty(couponEventId: Long)

    /** 앞에서부터 count 명을 꺼낸다. 꺼내기와 삭제가 원자적이라 한 명을 두 번 꺼내지 않는다. */
    fun popWaiting(couponEventId: Long, count: Long): List<Long>

    /** 0부터 시작하는 대기 순위. 대기열에 없으면 null */
    fun rank(couponEventId: Long, memberId: Long): Long?

    fun saveResults(
        couponEventId: Long,
        memberIds: List<Long>,
        result: CouponQueueResult,
    )

    fun findResult(couponEventId: Long, memberId: Long): CouponQueueResult?

    fun close(couponEventId: Long, reason: CouponQueueResult)

    fun closedReason(couponEventId: Long): CouponQueueResult?
}

/** 줄 서기 결과. 예전에는 -1, -2 같은 숫자로 돌려줬지만 의미를 타입으로 드러낸다. */
sealed interface EnqueueResult {
    /** @property rank 0부터 시작하는 순위 */
    data class Enqueued(
        val rank: Long,
    ) : EnqueueResult

    /** 이미 처리돼서 결과가 있다 */
    data object AlreadyProcessed : EnqueueResult

    /** 대기열이 닫혔다 (소진·종료) */
    data object Closed : EnqueueResult
}
