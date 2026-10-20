package com.site.domain.coupon.entity

/**
 * enum 상수마다 허용 범위(IntRange)를 갖게 해서, "정률은 1~100%" 같은 규칙을 if 문 대신 데이터로 표현한다.
 */
enum class DiscountType(
    private val validRange: IntRange,
) {
    /** 정액 할인 (원) */
    FIXED_AMOUNT(1..1_000_000),

    /** 정률 할인 (%) */
    PERCENTAGE(1..100),
    ;

    fun isValidValue(value: Int): Boolean = value in validRange
}
