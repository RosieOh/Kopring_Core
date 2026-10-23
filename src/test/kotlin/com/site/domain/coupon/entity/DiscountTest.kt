package com.site.domain.coupon.entity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiscountTest {

    @Test
    fun `정액 할인은 주문 금액을 넘지 않는다`() {
        val discount = Discount(DiscountType.FIXED_AMOUNT, 5_000)

        assertEquals(5_000, discount.calculate(orderAmount = 20_000))
        assertEquals(3_000, discount.calculate(orderAmount = 3_000))
    }

    @Test
    fun `정률 할인은 원 단위 미만을 버린다`() {
        val discount = Discount(DiscountType.PERCENTAGE, 15)

        assertEquals(1_500, discount.calculate(orderAmount = 10_000))
        assertEquals(1_498, discount.calculate(orderAmount = 9_990)) // 1498.5 → 1498
    }

    @Test
    fun `정률 할인은 큰 금액에서도 넘치지 않는다`() {
        // Int 끼리 곱하면 21억을 넘어 음수가 될 수 있어서 Long 으로 계산한다
        assertEquals(1_000_000_000, Discount(DiscountType.PERCENTAGE, 50).calculate(orderAmount = 2_000_000_000))
    }
}
