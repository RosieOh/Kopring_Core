package com.site.domain.coupon.entity

import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import java.time.LocalDateTime

/**
 * DB 에 저장하지 않고 "현재 시각 + 발급 수량"으로 그때그때 계산한다.
 * 저장해두면 시작/종료 시각이 지날 때마다 상태를 바꿔줄 배치가 필요해진다.
 */
enum class CouponEventStatus {
    /** 시작 전 */
    UPCOMING,

    /** 진행 중 (발급 가능) */
    ONGOING,

    /** 진행 기간이지만 수량 소진 */
    SOLD_OUT,

    /** 종료 */
    ENDED,
    ;

    /**
     * 발급할 수 있는 상태가 아니라면 이유에 맞는 예외를 던진다.
     * enum 을 대상으로 한 when 은 모든 값을 다루지 않으면 컴파일 에러가 난다. 나중에 상태가 추가돼도 여기서 놓치지 않는다.
     */
    fun checkIssuable() {
        when (this) {
            UPCOMING -> throw ServiceException(ErrorCode.COUPON_EVENT_NOT_STARTED)
            ENDED -> throw ServiceException(ErrorCode.COUPON_EVENT_ENDED)
            SOLD_OUT -> throw ServiceException(ErrorCode.COUPON_SOLD_OUT)
            ONGOING -> Unit
        }
    }

    companion object {
        fun of(
            period: EventPeriod,
            soldOut: Boolean,
            now: LocalDateTime,
        ): CouponEventStatus =
            when {
                period.isUpcoming(now) -> UPCOMING
                period.isEnded(now) -> ENDED
                soldOut -> SOLD_OUT
                else -> ONGOING
            }
    }
}
