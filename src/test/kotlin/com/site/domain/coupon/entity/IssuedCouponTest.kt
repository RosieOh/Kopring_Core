package com.site.domain.coupon.entity

import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class IssuedCouponTest : BehaviorSpec({

    val now = LocalDateTime.of(2026, 11, 1, 10, 0)
    val discount = Discount(DiscountType.FIXED_AMOUNT, 5_000)

    fun coupon(expiresAt: LocalDateTime = now.plusDays(30)) = IssuedCoupon(couponEventId = 1L, memberId = 1L, expiresAt = expiresAt)

    Given("아직 쓰지 않은 5천원 쿠폰") {
        val coupon = coupon()

        When("2만원 주문에 쓰면") {
            val discountAmount = coupon.use(orderAmount = 20_000, discount = discount, now = now)

            Then("5천원을 할인하고 사용 상태가 된다") {
                discountAmount shouldBe 5_000
                coupon.status(now) shouldBe IssuedCouponStatus.USED
                coupon.usedAt shouldBe now
            }
            Then("다시 쓸 수 없다") {
                shouldThrow<ServiceException> { coupon.use(orderAmount = 20_000, discount = discount, now = now) }.errorCode shouldBe
                    ErrorCode.ALREADY_USED_COUPON
            }
        }
    }

    Given("지금 시각에 만료되는 쿠폰") {
        val coupon = coupon(expiresAt = now)

        Then("만료 1초 전까지는 사용 가능, 만료 시각부터는 EXPIRED") {
            coupon.status(now.minusSeconds(1)) shouldBe IssuedCouponStatus.AVAILABLE
            coupon.status(now) shouldBe IssuedCouponStatus.EXPIRED
        }
        Then("쓸 수 없다") {
            shouldThrow<ServiceException> { coupon.use(orderAmount = 20_000, discount = discount, now = now) }.errorCode shouldBe
                ErrorCode.EXPIRED_COUPON
        }
    }
})
