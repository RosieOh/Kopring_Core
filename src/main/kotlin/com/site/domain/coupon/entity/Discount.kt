package com.site.domain.coupon.entity

import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * 할인 정책 값 객체. coupon_event 테이블의 컬럼 두 개(discount_type, discount_value)로 저장된다.
 *
 * init 블록은 우리가 생성자를 호출할 때만 실행된다. (JPA 가 DB 에서 읽어 올 때 쓰는 기본 생성자는
 * kotlin("plugin.jpa") 가 만든 것이라 init 을 거치지 않는다) 그래서 "잘못된 값으로는 만들 수조차 없는" 객체가 된다.
 */
@Embeddable
class Discount(
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "discount_type", nullable = false, length = 20)
    val type: DiscountType,

    @Column(name = "discount_value", nullable = false)
    val value: Int,
) {
    init {
        if (!type.isValidValue(value)) {
            throw ServiceException(ErrorCode.INVALID_DISCOUNT_VALUE)
        }
    }

    /**
     * 주문 금액에 적용할 할인액. 할인액은 주문 금액을 넘지 않는다.
     * 정률 할인은 원 단위 미만을 버린다. (Int 나눗셈은 소수점을 버린다)
     */
    fun calculate(orderAmount: Int): Int {
        require(orderAmount > 0) { "orderAmount must be positive: $orderAmount" }
        return when (type) {
            DiscountType.FIXED_AMOUNT -> minOf(value, orderAmount)
            DiscountType.PERCENTAGE -> (orderAmount.toLong() * value / 100).toInt()
        }
    }

    /** 사람이 읽는 표기. 5000 → "5,000원", 10 → "10%" */
    fun toDisplayText(): String =
        when (type) {
            DiscountType.FIXED_AMOUNT -> "%,d원".format(value)
            DiscountType.PERCENTAGE -> "$value%"
        }
}
