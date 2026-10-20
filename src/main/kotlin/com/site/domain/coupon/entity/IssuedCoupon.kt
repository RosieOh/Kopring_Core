package com.site.domain.coupon.entity

import com.site.domain.common.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 회원에게 발급된 쿠폰. 발급 시각은 createdAt 이다.
 *
 * - 다른 애그리거트(CouponEvent, Member)는 @ManyToOne 객체 대신 ID 로 참조한다.
 *   지연 로딩/연관관계 관리 없이 단순해지고, 애그리거트 사이 경계가 분명해진다.
 * - (coupon_event_id, member_id) unique 제약이 "1인 1매"의 최후 방어선이다.
 * - 한 번 정해지면 바뀌지 않는 값이라 var + protected set 대신 val 로 둔다.
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
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Column(name = "coupon_event_id", nullable = false)
    val couponEventId: Long = couponEventId

    @Column(name = "member_id", nullable = false)
    val memberId: Long = memberId
}
