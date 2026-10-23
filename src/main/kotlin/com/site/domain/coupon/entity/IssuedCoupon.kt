package com.site.domain.coupon.entity

import com.site.domain.common.entity.BaseTimeEntity
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.LocalDateTime

/**
 * 회원에게 발급된 쿠폰. 발급 시각은 createdAt 이다.
 *
 * - 다른 애그리거트(CouponEvent, Member)는 @ManyToOne 객체 대신 ID 로 참조한다.
 * - (coupon_event_id, member_id) unique 제약이 "1인 1매"의 최후 방어선이다.
 * - 같은 쿠폰을 동시에 두 번 쓰는 것은 낙관적 락(@Version)으로 막는다.
 *   UPDATE ... WHERE version = ? 가 0건이면 다른 요청이 먼저 바꾼 것이므로 실패시킨다.
 *   충돌이 드문 작업(한 사람이 자기 쿠폰을 쓰는 일)이라 매번 잠그는 비관적 락보다 가볍다.
 */
@Entity
@Table(
    name = "issued_coupon",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_issued_coupon_event_member", columnNames = ["coupon_event_id", "member_id"]),
    ],
)
class IssuedCoupon(
    couponEventId: Long,
    memberId: Long,
    expiresAt: LocalDateTime,
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Column(name = "coupon_event_id", nullable = false)
    val couponEventId: Long = couponEventId

    @Column(name = "member_id", nullable = false)
    val memberId: Long = memberId

    /** 발급 시점에 정해진다. 이벤트의 유효기간이 나중에 바뀌어도 이미 받은 쿠폰에는 영향이 없다. */
    @Column(nullable = false)
    val expiresAt: LocalDateTime = expiresAt

    var usedAt: LocalDateTime? = null
        protected set

    var discountAmount: Int? = null
        protected set

    @Version
    var version: Long = 0L
        protected set

    fun status(now: LocalDateTime): IssuedCouponStatus =
        when {
            usedAt != null -> IssuedCouponStatus.USED
            !now.isBefore(expiresAt) -> IssuedCouponStatus.EXPIRED
            else -> IssuedCouponStatus.AVAILABLE
        }

    fun isOwnedBy(memberId: Long): Boolean = this.memberId == memberId

    /** 쿠폰을 쓰고 할인액을 돌려준다. */
    fun use(
        orderAmount: Int,
        discount: Discount,
        now: LocalDateTime,
    ): Int {
        when (status(now)) {
            IssuedCouponStatus.USED -> throw ServiceException(ErrorCode.ALREADY_USED_COUPON)
            IssuedCouponStatus.EXPIRED -> throw ServiceException(ErrorCode.EXPIRED_COUPON)
            IssuedCouponStatus.AVAILABLE -> Unit
        }

        val amount = discount.calculate(orderAmount)
        usedAt = now
        discountAmount = amount
        return amount
    }
}
