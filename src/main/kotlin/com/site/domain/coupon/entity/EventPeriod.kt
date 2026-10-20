package com.site.domain.coupon.entity

import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.LocalDateTime

/**
 * 발급 기간 값 객체. [startAt, endAt) — 시작 시각은 포함, 종료 시각은 포함하지 않는다.
 *
 * "시작 < 종료"는 언제나 참이어야 하는 규칙이라 init 에서 검사한다.
 * 반면 "종료가 현재 이후"는 시간이 흐르면 깨지는 규칙이라 객체 불변식이 아니다. 그래서 now 를 받는 메서드로 분리했다.
 */
@Embeddable
class EventPeriod(
    @Column(name = "start_at", nullable = false)
    val startAt: LocalDateTime,

    @Column(name = "end_at", nullable = false)
    val endAt: LocalDateTime,
) {
    init {
        if (!startAt.isBefore(endAt)) {
            throw ServiceException(ErrorCode.INVALID_COUPON_EVENT_PERIOD)
        }
    }

    fun isUpcoming(now: LocalDateTime): Boolean = now.isBefore(startAt)

    fun isEnded(now: LocalDateTime): Boolean = !now.isBefore(endAt)

    fun requireNotEnded(now: LocalDateTime) {
        if (isEnded(now)) {
            throw ServiceException(ErrorCode.INVALID_COUPON_EVENT_PERIOD)
        }
    }
}
