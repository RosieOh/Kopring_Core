package com.site.presentation.coupon.dto

import com.site.domain.coupon.queue.CouponQueueResult
import com.site.domain.coupon.queue.CouponQueueStatus

/**
 * 클라이언트는 retryAfterMillis 뒤에 다시 조회(polling)해서 순번이 줄어드는 것을 보여주다가,
 * state 가 DONE 이 되면 result 로 결과를 안내한다. (SSE 스트림을 쓰면 다시 조회할 필요가 없다)
 */
data class CouponQueueStatusResponse(
    val state: State,
    val position: Long? = null,
    val result: CouponQueueResult? = null,
    /** 다음 조회까지 기다릴 시간. 앞 순번은 짧고 뒤 순번은 길다. 더 물을 필요가 없으면 null */
    val retryAfterMillis: Long? = null,
) {
    enum class State { WAITING, PROCESSING, DONE, NOT_IN_QUEUE }
}

// sealed 타입을 when 으로 펼친다. is 로 확인한 분기 안에서는 position / result 를 바로 꺼내 쓸 수 있다 (스마트 캐스트)
fun CouponQueueStatus.toResponse(retryAfterMillis: Long? = null): CouponQueueStatusResponse =
    when (this) {
        is CouponQueueStatus.Waiting -> CouponQueueStatusResponse(
            CouponQueueStatusResponse.State.WAITING,
            position = position,
            retryAfterMillis = retryAfterMillis,
        )
        CouponQueueStatus.Processing -> CouponQueueStatusResponse(CouponQueueStatusResponse.State.PROCESSING, retryAfterMillis = retryAfterMillis)
        is CouponQueueStatus.Done -> CouponQueueStatusResponse(CouponQueueStatusResponse.State.DONE, result = result)
        CouponQueueStatus.NotInQueue -> CouponQueueStatusResponse(CouponQueueStatusResponse.State.NOT_IN_QUEUE)
    }
