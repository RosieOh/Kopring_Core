package com.site.domain.coupon.repository

import com.site.domain.coupon.entity.CouponEvent
import org.springframework.data.jpa.repository.JpaRepository

interface CouponEventRepository : JpaRepository<CouponEvent, Long>
