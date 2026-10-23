package com.site.domain.coupon.repository

import com.site.domain.coupon.entity.IssuedCoupon
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface IssuedCouponRepository : JpaRepository<IssuedCoupon, Long> {

    fun existsByCouponEventIdAndMemberId(couponEventId: Long, memberId: Long): Boolean

    fun countByCouponEventId(couponEventId: Long): Long

    fun findByMemberId(memberId: Long, pageable: Pageable): Page<IssuedCoupon>
}
