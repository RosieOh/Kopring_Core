package com.site.presentation.coupon.dto

import com.site.domain.coupon.queue.CouponQueueResult
import com.site.domain.coupon.queue.CouponQueueStatus

/**
 * 클라이언트는 이 응답을 1초 정도 간격으로 다시 조회(polling)해서 순번이 줄어드는 것을 보여주다가,
 * state 가 DONE 이 되면 result 로 결과를 안내한다.
 */
data class CouponQueueStatusResponse(
    val state: State,
    val position: Long? = null,
    val result: CouponQueueResult? = null,
) {
    enum class State { WAITING, DONE, NOT_IN_QUEUE }
}

// sealed 타입을 when 으로 펼친다. is 로 확인한 분기 안에서는 position / result 를 바로 꺼내 쓸 수 있다 (스마트 캐스트)
fun CouponQueueStatus.toResponse(): CouponQueueStatusResponse =
    when (this) {
        is CouponQueueStatus.Waiting -> CouponQueueStatusResponse(CouponQueueStatusResponse.State.WAITING, position = position)
        is CouponQueueStatus.Done -> CouponQueueStatusResponse(CouponQueueStatusResponse.State.DONE, result = result)
        CouponQueueStatus.NotInQueue -> CouponQueueStatusResponse(CouponQueueStatusResponse.State.NOT_IN_QUEUE)
    }
