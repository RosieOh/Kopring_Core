package com.site.domain.coupon.entity

import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class CouponEventTest {

    private val startAt = LocalDateTime.of(2026, 11, 1, 10, 0)
    private val endAt = startAt.plusDays(7)

    // 기본값 파라미터 덕분에 테스트마다 "관심 있는 값"만 바꿔서 만들 수 있다
    private fun couponEvent(
        discount: Discount = Discount(DiscountType.FIXED_AMOUNT, 5_000),
        period: EventPeriod = EventPeriod(startAt, endAt),
        totalQuantity: Int = 100,
    ) = CouponEvent(name = "신규 가입 할인", discount = discount, period = period, totalQuantity = totalQuantity)

    @Test
    fun `시작 전에는 UPCOMING, 기간 중에는 ONGOING, 종료 시각부터는 ENDED`() {
        val couponEvent = couponEvent()

        assertEquals(CouponEventStatus.UPCOMING, couponEvent.status(startAt.minusSeconds(1)))
        assertEquals(CouponEventStatus.ONGOING, couponEvent.status(startAt))
        assertEquals(CouponEventStatus.ONGOING, couponEvent.status(endAt.minusSeconds(1)))
        assertEquals(CouponEventStatus.ENDED, couponEvent.status(endAt))
    }

    @Test
    fun `종료 시각이 시작 시각보다 늦지 않으면 기간을 만들 수 없다`() {
        val exception = assertThrows<ServiceException> { EventPeriod(startAt, startAt) }

        assertEquals(ErrorCode.INVALID_COUPON_EVENT_PERIOD, exception.errorCode)
    }

    @Test
    fun `정률 할인은 100퍼센트까지만 허용한다`() {
        assertDoesNotThrow { Discount(DiscountType.PERCENTAGE, 100) }

        val exception = assertThrows<ServiceException> { Discount(DiscountType.PERCENTAGE, 101) }
        assertEquals(ErrorCode.INVALID_DISCOUNT_VALUE, exception.errorCode)
    }

    @Test
    fun `발급할 때마다 남은 수량이 줄고, 다 떨어지면 SOLD_OUT 이 되어 더 발급할 수 없다`() {
        val couponEvent = couponEvent(totalQuantity = 2)

        repeat(2) { couponEvent.issue(startAt) }

        assertEquals(0, couponEvent.remainingQuantity)
        assertEquals(CouponEventStatus.SOLD_OUT, couponEvent.status(startAt))
        val exception = assertThrows<ServiceException> { couponEvent.issue(startAt) }
        assertEquals(ErrorCode.COUPON_SOLD_OUT, exception.errorCode)
    }

    @Test
    fun `시작 전이나 종료 후에는 발급할 수 없다`() {
        val couponEvent = couponEvent()

        val beforeStart = assertThrows<ServiceException> { couponEvent.issue(startAt.minusSeconds(1)) }
        val afterEnd = assertThrows<ServiceException> { couponEvent.issue(endAt) }

        assertEquals(ErrorCode.COUPON_EVENT_NOT_STARTED, beforeStart.errorCode)
        assertEquals(ErrorCode.COUPON_EVENT_ENDED, afterEnd.errorCode)
    }

    @Test
    fun `시작 전에는 수정할 수 있다`() {
        val couponEvent = couponEvent()

        couponEvent.update(
            name = "수정된 이벤트",
            discount = Discount(DiscountType.PERCENTAGE, 10),
            period = EventPeriod(startAt.plusDays(1), endAt.plusDays(1)),
            totalQuantity = 50,
            issueMode = CouponIssueMode.QUEUE,
            couponValidityDays = 7,
            now = startAt.minusDays(1),
        )

        assertEquals("수정된 이벤트", couponEvent.name)
        assertEquals(DiscountType.PERCENTAGE, couponEvent.discount.type)
        assertEquals(50, couponEvent.remainingQuantity)
        assertEquals(CouponIssueMode.QUEUE, couponEvent.issueMode)
        assertEquals(startAt.plusDays(7), couponEvent.couponExpiresAt(issuedAt = startAt))
    }

    @Test
    fun `발급 방식이 다른 요청은 거절한다`() {
        val queueEvent = CouponEvent("대기열", Discount(DiscountType.FIXED_AMOUNT, 1_000), EventPeriod(startAt, endAt), 10, CouponIssueMode.QUEUE)
        val directEvent = couponEvent()

        assertDoesNotThrow { queueEvent.checkIssueMode(CouponIssueMode.QUEUE) }
        assertEquals(
            ErrorCode.QUEUE_ONLY_COUPON_EVENT,
            assertThrows<ServiceException> { queueEvent.checkIssueMode(CouponIssueMode.DIRECT) }.errorCode,
        )
        assertEquals(
            ErrorCode.DIRECT_ONLY_COUPON_EVENT,
            assertThrows<ServiceException> { directEvent.checkIssueMode(CouponIssueMode.QUEUE) }.errorCode,
        )
    }

    @Test
    fun `시작된 이벤트는 수정할 수 없다`() {
        val couponEvent = couponEvent()

        val exception = assertThrows<ServiceException> {
            couponEvent.update(
                name = "수정된 이벤트",
                discount = couponEvent.discount,
                period = couponEvent.period,
                totalQuantity = 50,
                issueMode = couponEvent.issueMode,
                couponValidityDays = couponEvent.couponValidityDays,
                now = startAt,
            )
        }

        assertEquals(ErrorCode.COUPON_EVENT_ALREADY_STARTED, exception.errorCode)
    }
}
