package com.site.domain.coupon.repository

import com.site.domain.coupon.entity.IssuedCoupon
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface IssuedCouponRepository : JpaRepository<IssuedCoupon, Long> {

    fun existsByCouponEventIdAndMemberId(couponEventId: Long, memberId: Long): Boolean

    fun countByCouponEventId(couponEventId: Long): Long

    fun findByMemberId(memberId: Long, pageable: Pageable): Page<IssuedCoupon>

    /** 주어진 회원 중 이미 이 이벤트의 쿠폰을 받은 회원 (일괄 발급에서 한 번에 확인) */
    @Query("select c.memberId from IssuedCoupon c where c.couponEventId = :couponEventId and c.memberId in :memberIds")
    fun findIssuedMemberIds(couponEventId: Long, memberIds: Collection<Long>): List<Long>
}
