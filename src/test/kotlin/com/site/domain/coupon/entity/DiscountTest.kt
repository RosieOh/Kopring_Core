package com.site.domain.coupon.entity

import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

/**
 * 입력과 기대값이 표로 정리되는 테스트는 withData 로 쓴다. 행마다 별도의 테스트로 실행되고 이름이 붙는다.
 */
class DiscountTest : FunSpec({

    context("할인 값은 방식별 허용 범위 안이어야 한다") {
        withData(
            mapOf(
                "정액 1원" to Triple(DiscountType.FIXED_AMOUNT, 1, true),
                "정액 1,000,000원" to Triple(DiscountType.FIXED_AMOUNT, 1_000_000, true),
                "정액 0원" to Triple(DiscountType.FIXED_AMOUNT, 0, false),
                "정액 1,000,001원" to Triple(DiscountType.FIXED_AMOUNT, 1_000_001, false),
                "정률 1%" to Triple(DiscountType.PERCENTAGE, 1, true),
                "정률 100%" to Triple(DiscountType.PERCENTAGE, 100, true),
                "정률 101%" to Triple(DiscountType.PERCENTAGE, 101, false),
            ),
        ) { (type, value, valid) ->
            if (valid) {
                shouldNotThrowAny { Discount(type, value) }
            } else {
                shouldThrow<ServiceException> { Discount(type, value) }.errorCode shouldBe ErrorCode.INVALID_DISCOUNT_VALUE
            }
        }
    }

    context("정액 할인은 주문 금액을 넘지 않는다") {
        val discount = Discount(DiscountType.FIXED_AMOUNT, 5_000)
        withData(
            mapOf(
                "주문 20,000원 → 5,000원" to (20_000 to 5_000),
                "주문 5,000원 → 5,000원" to (5_000 to 5_000),
                "주문 3,000원 → 3,000원" to (3_000 to 3_000),
            ),
        ) { (orderAmount, expected) ->
            discount.calculate(orderAmount) shouldBe expected
        }
    }

    context("정률 할인은 원 단위 미만을 버린다") {
        val discount = Discount(DiscountType.PERCENTAGE, 15)
        withData(
            mapOf(
                "주문 10,000원 → 1,500원" to (10_000 to 1_500),
                "주문 9,990원 → 1,498원 (1,498.5 버림)" to (9_990 to 1_498),
                "주문 1원 → 0원" to (1 to 0),
            ),
        ) { (orderAmount, expected) ->
            discount.calculate(orderAmount) shouldBe expected
        }
    }

    test("정률 할인은 큰 금액에서도 넘치지 않는다 (Int 끼리 곱하면 21억을 넘어 음수가 된다)") {
        Discount(DiscountType.PERCENTAGE, 50).calculate(orderAmount = 2_000_000_000) shouldBe 1_000_000_000
    }

    test("사람이 읽는 표기") {
        Discount(DiscountType.FIXED_AMOUNT, 5_000).toDisplayText() shouldBe "5,000원"
        Discount(DiscountType.PERCENTAGE, 10).toDisplayText() shouldBe "10%"
    }
})
