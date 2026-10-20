package com.site.domain.coupon.entity

import com.site.domain.common.entity.BaseTimeEntity
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 선착순 쿠폰 발급 이벤트.
 *
 * 시간에 따라 달라지는 판단(status, 수정 가능 여부)은 LocalDateTime.now() 를 직접 부르지 않고 now 를 인자로 받는다.
 * 덕분에 이 클래스는 스프링 없이, 시각을 마음대로 넣어가며 단위 테스트할 수 있다.
 */
@Entity
@Table(name = "coupon_event")
class CouponEvent(
    name: String,
    discount: Discount,
    period: EventPeriod,
    totalQuantity: Int,
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Column(nullable = false, length = 50)
    var name: String = name
        protected set

    @Embedded
    var discount: Discount = discount
        protected set

    @Embedded
    var period: EventPeriod = period
        protected set

    @Column(nullable = false)
    var totalQuantity: Int = totalQuantity
        protected set

    // 3단계(선착순 발급)에서 동시성 문제의 주인공이 될 컬럼
    @Column(nullable = false)
    var issuedQuantity: Int = 0
        protected set

    // backing field 가 없는 계산 프로퍼티는 컬럼으로 매핑되지 않는다
    val remainingQuantity: Int
        get() = totalQuantity - issuedQuantity

    init {
        // 요청 DTO 검증(@Positive)을 통과했다면 절대 깨질 수 없는 조건 → 프로그래밍 오류이므로 require
        require(totalQuantity > 0) { "totalQuantity must be positive: $totalQuantity" }
    }

    fun status(now: LocalDateTime): CouponEventStatus = when {
        period.isUpcoming(now) -> CouponEventStatus.UPCOMING
        period.isEnded(now) -> CouponEventStatus.ENDED
        issuedQuantity >= totalQuantity -> CouponEventStatus.SOLD_OUT
        else -> CouponEventStatus.ONGOING
    }

    fun update(
        name: String,
        discount: Discount,
        period: EventPeriod,
        totalQuantity: Int,
        now: LocalDateTime,
    ) {
        checkModifiable(now)
        require(totalQuantity > 0) { "totalQuantity must be positive: $totalQuantity" }

        this.name = name
        this.discount = discount
        this.period = period
        this.totalQuantity = totalQuantity
    }

    /** 시작된 이벤트는 이미 쿠폰을 받은 사람이 있을 수 있으므로 수정·삭제를 막는다. */
    fun checkModifiable(now: LocalDateTime) {
        if (status(now) != CouponEventStatus.UPCOMING) {
            throw ServiceException(ErrorCode.COUPON_EVENT_ALREADY_STARTED)
        }
    }
}
