package com.site.domain.coupon.service

import com.site.domain.coupon.event.CouponIssuedEvent
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.member.entity.Member
import com.site.domain.member.repository.MemberRepository
import com.site.domain.notification.NotificationResult
import com.site.domain.notification.port.AlimtalkMessage
import com.site.domain.notification.port.AlimtalkSendResult
import com.site.domain.notification.port.AlimtalkSender
import com.site.domain.notification.port.AlimtalkTemplate
import com.site.support.couponEventFixture
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Optional

class CouponNotificationServiceTest : BehaviorSpec({

    val memberRepository = mockk<MemberRepository>()
    val couponEventRepository = mockk<CouponEventRepository>()
    val alimtalkSender = mockk<AlimtalkSender>()
    val couponNotificationService = CouponNotificationService(memberRepository, couponEventRepository, alimtalkSender)

    val event = CouponIssuedEvent(issuedCouponId = 1L, couponEventId = 10L, memberId = 100L)

    fun member(phoneNumber: String?) = Member(email = "user@test.com", password = "encoded", nickname = "로지", phoneNumber = phoneNumber)

    // findByIdOrNull 은 확장 함수라 직접 모킹하기 번거롭다. 내부에서 부르는 findById 를 모킹한다.
    every { couponEventRepository.findById(10L) } returns Optional.of(couponEventFixture(name = "오픈 기념"))

    Given("휴대폰 번호가 있는 회원") {
        every { memberRepository.findById(100L) } returns Optional.of(member(phoneNumber = "01012345678"))

        When("발송에 성공하면") {
            val sent = slot<AlimtalkMessage>()
            every { alimtalkSender.send(capture(sent)) } returns AlimtalkSendResult.Success

            val result = couponNotificationService.notifyIssued(event)

            Then("SENT 이고, 닉네임·이벤트명·할인 문구를 템플릿 변수로 담아 그 번호로 보낸다") {
                result shouldBe NotificationResult.Sent
                sent.captured.to shouldBe "01012345678"
                sent.captured.template shouldBe AlimtalkTemplate.COUPON_ISSUED
                sent.captured.variables shouldBe mapOf("#{닉네임}" to "로지", "#{이벤트명}" to "오픈 기념", "#{할인}" to "5,000원")
            }
        }

        When("발송에 실패하면") {
            every { alimtalkSender.send(any()) } returns AlimtalkSendResult.Failure(reason = "timeout", retryable = true)

            val result = couponNotificationService.notifyIssued(event)

            Then("한 번만 시도하고 재시도 가능 여부와 함께 돌려준다 (재시도는 Outbox 가 정한다)") {
                result shouldBe NotificationResult.Failed(reason = "timeout", retryable = true)
                verify(exactly = 1) { alimtalkSender.send(any()) }
            }
        }
    }

    Given("휴대폰 번호가 없는 회원") {
        every { memberRepository.findById(100L) } returns Optional.of(member(phoneNumber = null))

        Then("보내지 않고 SKIPPED") {
            couponNotificationService.notifyIssued(event) shouldBe NotificationResult.Skipped("휴대폰 번호가 없습니다")
            verify(exactly = 0) { alimtalkSender.send(any()) }
        }
    }
})
