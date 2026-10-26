package com.site.domain.coupon.entity

import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

/**
 * BehaviorSpec: Given(상황) / When(행동) / Then(결과) 로 비즈니스 규칙을 문장처럼 적는다.
 * 엔티티가 시각을 인자로 받기 때문에 스프링 없이 원하는 시각을 넣어 검증할 수 있다.
 */
class CouponEventTest : BehaviorSpec({

    val startAt = LocalDateTime.of(2026, 11, 1, 10, 0)
    val endAt = startAt.plusDays(7)

    fun couponEvent(totalQuantity: Int = 100, issueMode: CouponIssueMode = CouponIssueMode.DIRECT) =
        CouponEvent(
            name = "신규 가입 할인",
            discount = Discount(DiscountType.FIXED_AMOUNT, 5_000),
            period = EventPeriod(startAt, endAt),
            totalQuantity = totalQuantity,
            issueMode = issueMode,
        )

    Given("11월 1일 10시부터 일주일 동안 열리는 이벤트") {
        val couponEvent = couponEvent()

        Then("시작 1초 전에는 UPCOMING") {
            couponEvent.status(startAt.minusSeconds(1)) shouldBe CouponEventStatus.UPCOMING
        }
        Then("시작 시각부터 종료 1초 전까지는 ONGOING") {
            couponEvent.status(startAt) shouldBe CouponEventStatus.ONGOING
            couponEvent.status(endAt.minusSeconds(1)) shouldBe CouponEventStatus.ONGOING
        }
        Then("종료 시각부터는 ENDED") {
            couponEvent.status(endAt) shouldBe CouponEventStatus.ENDED
        }
    }

    Given("종료 시각이 시작 시각보다 늦지 않은 기간") {
        Then("기간을 만들 수 없다") {
            shouldThrow<ServiceException> { EventPeriod(startAt, startAt) }.errorCode shouldBe ErrorCode.INVALID_COUPON_EVENT_PERIOD
        }
    }

    Given("2장짜리 진행 중 이벤트") {
        val couponEvent = couponEvent(totalQuantity = 2)

        When("2장을 모두 발급하면") {
            repeat(2) { couponEvent.issue(startAt) }

            Then("남은 수량이 0 이고 SOLD_OUT 이 된다") {
                couponEvent.remainingQuantity shouldBe 0
                couponEvent.status(startAt) shouldBe CouponEventStatus.SOLD_OUT
            }
            Then("더 발급할 수 없다") {
                shouldThrow<ServiceException> { couponEvent.issue(startAt) }.errorCode shouldBe ErrorCode.COUPON_SOLD_OUT
            }
        }
    }

    Given("기간 밖의 시각") {
        val couponEvent = couponEvent()

        Then("시작 전에는 COUPON_EVENT_NOT_STARTED") {
            shouldThrow<ServiceException> { couponEvent.issue(startAt.minusSeconds(1)) }.errorCode shouldBe
                ErrorCode.COUPON_EVENT_NOT_STARTED
        }
        Then("종료 후에는 COUPON_EVENT_ENDED") {
            shouldThrow<ServiceException> { couponEvent.issue(endAt) }.errorCode shouldBe ErrorCode.COUPON_EVENT_ENDED
        }
    }

    Given("시작 전인 이벤트") {
        val couponEvent = couponEvent()

        When("내용을 바꾸면") {
            couponEvent.update(
                name = "수정된 이벤트",
                discount = Discount(DiscountType.PERCENTAGE, 10),
                period = EventPeriod(startAt.plusDays(1), endAt.plusDays(1)),
                totalQuantity = 50,
                issueMode = CouponIssueMode.QUEUE,
                couponValidityDays = 7,
                now = startAt.minusDays(1),
            )

            Then("바뀐 내용이 반영된다") {
                couponEvent.name shouldBe "수정된 이벤트"
                couponEvent.discount.type shouldBe DiscountType.PERCENTAGE
                couponEvent.remainingQuantity shouldBe 50
                couponEvent.issueMode shouldBe CouponIssueMode.QUEUE
            }
            Then("발급되는 쿠폰은 발급 시각 + 유효기간에 만료된다") {
                couponEvent.couponExpiresAt(issuedAt = startAt) shouldBe startAt.plusDays(7)
            }
        }
    }

    Given("이미 시작된 이벤트") {
        val couponEvent = couponEvent()

        Then("수정할 수 없다") {
            shouldThrow<ServiceException> {
                couponEvent.update(
                    name = "수정된 이벤트",
                    discount = couponEvent.discount,
                    period = couponEvent.period,
                    totalQuantity = 50,
                    issueMode = couponEvent.issueMode,
                    couponValidityDays = couponEvent.couponValidityDays,
                    now = startAt,
                )
            }.errorCode shouldBe ErrorCode.COUPON_EVENT_ALREADY_STARTED
        }
    }

    Given("발급 방식이 정해진 이벤트") {
        Then("대기열 이벤트에 즉시 발급을 요청하면 QUEUE_ONLY_COUPON_EVENT") {
            val queueEvent = couponEvent(issueMode = CouponIssueMode.QUEUE)
            shouldNotThrowAny { queueEvent.checkIssueMode(CouponIssueMode.QUEUE) }
            shouldThrow<ServiceException> { queueEvent.checkIssueMode(CouponIssueMode.DIRECT) }.errorCode shouldBe
                ErrorCode.QUEUE_ONLY_COUPON_EVENT
        }
        Then("즉시 발급 이벤트에 줄을 서려 하면 DIRECT_ONLY_COUPON_EVENT") {
            shouldThrow<ServiceException> { couponEvent().checkIssueMode(CouponIssueMode.QUEUE) }.errorCode shouldBe
                ErrorCode.DIRECT_ONLY_COUPON_EVENT
        }
    }
})
