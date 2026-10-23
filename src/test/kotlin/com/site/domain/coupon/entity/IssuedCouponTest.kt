package com.site.domain.coupon.entity

import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class IssuedCouponTest {

    private val now = LocalDateTime.of(2026, 11, 1, 10, 0)
    private val discount = Discount(DiscountType.FIXED_AMOUNT, 5_000)

    private fun coupon(expiresAt: LocalDateTime = now.plusDays(30)) = IssuedCoupon(couponEventId = 1L, memberId = 1L, expiresAt = expiresAt)

    @Test
    fun `쓰면 할인액을 돌려주고 사용 상태가 된다`() {
        val coupon = coupon()

        val discountAmount = coupon.use(orderAmount = 20_000, discount = discount, now = now)

        assertEquals(5_000, discountAmount)
        assertEquals(IssuedCouponStatus.USED, coupon.status(now))
        assertEquals(now, coupon.usedAt)
    }

    @Test
    fun `이미 쓴 쿠폰은 다시 쓸 수 없다`() {
        val coupon = coupon().apply { use(orderAmount = 20_000, discount = discount, now = now) }

        val exception = assertThrows<ServiceException> { coupon.use(orderAmount = 20_000, discount = discount, now = now) }

        assertEquals(ErrorCode.ALREADY_USED_COUPON, exception.errorCode)
    }

    @Test
    fun `만료 시각부터는 쓸 수 없다`() {
        val coupon = coupon(expiresAt = now)

        assertEquals(IssuedCouponStatus.AVAILABLE, coupon.status(now.minusSeconds(1)))
        assertEquals(IssuedCouponStatus.EXPIRED, coupon.status(now))
        val exception = assertThrows<ServiceException> { coupon.use(orderAmount = 20_000, discount = discount, now = now) }
        assertEquals(ErrorCode.EXPIRED_COUPON, exception.errorCode)
    }
}
