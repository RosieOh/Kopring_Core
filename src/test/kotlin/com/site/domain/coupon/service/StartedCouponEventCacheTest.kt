package com.site.domain.coupon.service

import com.site.domain.coupon.entity.CouponEvent
import com.site.domain.coupon.entity.CouponIssueMode
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.independent.config.ClockConfig
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import com.site.support.couponEventFixture
import com.site.support.runConcurrently
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.ConcurrentLinkedQueue

class StartedCouponEventCacheTest : FunSpec({

    val start = LocalDateTime.of(2026, 10, 26, 12, 0)
    var now = start.plusMinutes(1)
    val clock = mockk<Clock> {
        every { zone } returns ClockConfig.SERVICE_ZONE
        every { instant() } answers { now.atZone(ClockConfig.SERVICE_ZONE).toInstant() }
    }
    val couponEventRepository = mockk<CouponEventRepository>()
    val cache = StartedCouponEventCache(couponEventRepository, clock)

    fun givenEvent(couponEvent: CouponEvent) {
        // findByIdOrNull 은 확장 함수라 내부에서 부르는 findById 를 모킹한다
        every { couponEventRepository.findById(1L) } returns Optional.of(couponEvent)
    }

    fun event(totalQuantity: Int = 100, issueMode: CouponIssueMode = CouponIssueMode.DIRECT) =
        couponEventFixture(startAt = start, endAt = start.plusHours(1), totalQuantity = totalQuantity, issueMode = issueMode)

    fun errorCodeOf(mode: CouponIssueMode = CouponIssueMode.DIRECT) = shouldThrow<ServiceException> { cache.checkIssuable(1L, mode) }.errorCode

    test("진행 중인 이벤트는 처음 한 번만 DB 에서 읽는다") {
        givenEvent(event())

        repeat(3) { cache.checkIssuable(1L, CouponIssueMode.DIRECT) }

        verify(exactly = 1) { couponEventRepository.findById(1L) }
    }

    test("시작 전 이벤트는 아직 바뀔 수 있으므로 캐시하지 않는다") {
        givenEvent(event())
        now = start.minusMinutes(1)

        repeat(2) { errorCodeOf() shouldBe ErrorCode.COUPON_EVENT_NOT_STARTED }

        verify(exactly = 2) { couponEventRepository.findById(1L) }
    }

    test("소진을 확인한 뒤로는 DB 를 읽지 않고 바로 소진으로 거절한다") {
        givenEvent(event())
        cache.checkIssuable(1L, CouponIssueMode.DIRECT)

        cache.markSoldOut(1L)

        errorCodeOf() shouldBe ErrorCode.COUPON_SOLD_OUT
        verify(exactly = 1) { couponEventRepository.findById(1L) }
    }

    test("처음 읽을 때 이미 소진이었다면 소진으로 기억한다") {
        givenEvent(event(totalQuantity = 1).apply { issue(now) })

        repeat(2) { errorCodeOf() shouldBe ErrorCode.COUPON_SOLD_OUT }

        verify(exactly = 1) { couponEventRepository.findById(1L) }
    }

    test("이벤트와 다른 방식으로 요청하면 거절한다 (대기열 새치기 방지)") {
        givenEvent(event(issueMode = CouponIssueMode.QUEUE))

        errorCodeOf(CouponIssueMode.DIRECT) shouldBe ErrorCode.QUEUE_ONLY_COUPON_EVENT
        cache.checkIssuable(1L, CouponIssueMode.QUEUE)

        verify(exactly = 1) { couponEventRepository.findById(1L) }
    }

    test("종료 시각이 지나면 종료로 거절하고 캐시에서 뺀다") {
        givenEvent(event())
        cache.checkIssuable(1L, CouponIssueMode.DIRECT)

        now = start.plusHours(1)

        errorCodeOf() shouldBe ErrorCode.COUPON_EVENT_ENDED
        errorCodeOf() shouldBe ErrorCode.COUPON_EVENT_ENDED
        verify(exactly = 2) { couponEventRepository.findById(1L) }
    }

    test("캐시가 빈 채로 같은 이벤트에 요청이 몰려도 DB 는 한 번만 읽는다 (single-flight)") {
        every { couponEventRepository.findById(1L) } answers {
            Thread.sleep(500) // 읽는 동안 나머지 요청이 모두 도착한다
            Optional.of(event())
        }

        runConcurrently(taskCount = 50) { cache.checkIssuable(1L, CouponIssueMode.DIRECT) }

        verify(exactly = 1) { couponEventRepository.findById(1L) }
    }

    test("함께 기다린 요청들은 먼저 읽은 요청의 실패를 같이 받고, 다음 요청은 다시 읽는다") {
        every { couponEventRepository.findById(1L) } answers {
            Thread.sleep(500)
            Optional.empty()
        }
        val errors = ConcurrentLinkedQueue<ErrorCode>()

        runConcurrently(taskCount = 10) {
            errors += shouldThrow<ServiceException> { cache.checkIssuable(1L, CouponIssueMode.DIRECT) }.errorCode
        }
        errorCodeOf() shouldBe ErrorCode.COUPON_EVENT_NOT_FOUND

        errors.toSet() shouldBe setOf(ErrorCode.COUPON_EVENT_NOT_FOUND)
        verify(exactly = 2) { couponEventRepository.findById(1L) }
    }

    test("없는 이벤트는 NOT_FOUND") {
        every { couponEventRepository.findById(1L) } returns Optional.empty()

        errorCodeOf() shouldBe ErrorCode.COUPON_EVENT_NOT_FOUND
    }
})
