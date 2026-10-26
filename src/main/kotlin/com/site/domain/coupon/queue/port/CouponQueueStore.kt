package com.site.domain.coupon.queue.port

import com.site.domain.coupon.queue.CouponQueueResult

/**
 * 선착순 대기열 저장소 포트. 어떤 연산이 "원자적"이어야 하는지가 이 인터페이스의 계약이다.
 *
 * 회원은 대기(waiting) → 처리 중(processing) → 결과(result) 순서로 옮겨 간다.
 * 처리 중에는 lease(만료 시각)가 붙어서, 처리하던 워커가 죽으면 lease 가 지난 뒤 다른 워커가 다시 가져간다.
 */
interface CouponQueueStore {
    /** 결과 확인 → 처리 중 확인 → 마감 확인 → 대기 중 확인 → 순번 발급 → 추가를 원자적으로 한다. */
    fun enqueue(couponEventId: Long, memberId: Long): EnqueueResult

    fun activate(couponEventId: Long)

    fun activeEventIds(): Set<Long>

    fun deactivate(couponEventId: Long)

    /** 대기·처리 중이 모두 비어 있으면 순회 대상에서 뺀다. 빼는 사이에 누가 들어왔다면 다시 넣는다. */
    fun deactivateIfEmpty(couponEventId: Long)

    /** 앞에서부터 count 명을 꺼내 처리 중으로 옮긴다. (원자적 — 한 명을 두 워커가 가져가지 않는다) */
    fun claimNext(
        couponEventId: Long,
        count: Long,
        leaseUntilMillis: Long,
    ): List<Long>

    /** lease 가 지난 처리 중 회원(처리하던 워커가 죽었다)을 다시 가져온다. lease 를 새로 걸어 다른 워커와 겹치지 않게 한다. */
    fun claimExpired(
        couponEventId: Long,
        nowMillis: Long,
        count: Long,
        leaseUntilMillis: Long,
    ): List<Long>

    /** 결과를 기록하고 처리 중에서 뺀다. (원자적 — 결과는 있는데 처리 중으로 남거나, 그 반대가 되지 않게) */
    fun complete(
        couponEventId: Long,
        memberIds: List<Long>,
        result: CouponQueueResult,
    )

    /** 대기 중인 회원을 count 명까지 꺼내 바로 결과를 기록한다. (마감 후 정리용, 원자적) 정리한 인원 수를 돌려준다. */
    fun drainWaiting(
        couponEventId: Long,
        count: Long,
        result: CouponQueueResult,
    ): Long

    /** 0부터 시작하는 대기 순위. 대기열에 없으면 null */
    fun rank(couponEventId: Long, memberId: Long): Long?

    fun isProcessing(couponEventId: Long, memberId: Long): Boolean

    fun waitingCount(couponEventId: Long): Long

    fun processingCount(couponEventId: Long): Long

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

    /** 이미 처리 중이거나 결과가 있다 */
    data object AlreadyProcessed : EnqueueResult

    /** 대기열이 닫혔다 (소진·종료) */
    data object Closed : EnqueueResult
}
