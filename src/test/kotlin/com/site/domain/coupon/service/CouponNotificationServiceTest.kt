package com.site.domain.coupon.service

import com.site.domain.coupon.event.CouponIssuedEvent
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.member.entity.Member
import com.site.domain.member.repository.MemberRepository
import com.site.infrastructure.biztalk.AlimtalkMessage
import com.site.infrastructure.biztalk.AlimtalkSendResult
import com.site.infrastructure.biztalk.AlimtalkSender
import com.site.infrastructure.biztalk.BiztalkProperties
import com.site.support.couponEventFixture
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Optional

class CouponNotificationServiceTest {

    private val memberRepository = mockk<MemberRepository>()
    private val couponEventRepository = mockk<CouponEventRepository>()
    private val alimtalkSender = mockk<AlimtalkSender>()

    private val couponNotificationService = CouponNotificationService(
        memberRepository = memberRepository,
        couponEventRepository = couponEventRepository,
        alimtalkSender = alimtalkSender,
        biztalkProperties = BiztalkProperties(couponIssuedTemplateId = "COUPON_ISSUED"),
        retryProperties = NotificationRetryProperties(maxAttempts = 3, backoff = Duration.ZERO),
    )

    private val event = CouponIssuedEvent(issuedCouponId = 1L, couponEventId = 10L, memberId = 100L)

    private val retryableFailure = AlimtalkSendResult.Failure(reason = "timeout", retryable = true)

    @BeforeEach
    fun setUp() {
        // findByIdOrNull 은 확장 함수라 직접 모킹하기 번거롭다. 내부에서 부르는 findById 를 모킹한다.
        every { memberRepository.findById(100L) } returns Optional.of(member(phoneNumber = "01012345678"))
        every { couponEventRepository.findById(10L) } returns Optional.of(couponEventFixture(name = "오픈 기념"))
    }

    @Test
    fun `닉네임, 이벤트명, 할인 문구를 템플릿 변수로 담아 회원 휴대폰으로 보낸다`() {
        val sent = slot<AlimtalkMessage>()
        every { alimtalkSender.send(capture(sent)) } returns AlimtalkSendResult.Success

        couponNotificationService.notifyIssued(event)

        assertEquals("01012345678", sent.captured.to)
        assertEquals("COUPON_ISSUED", sent.captured.templateId)
        assertEquals(
            mapOf("#{닉네임}" to "로지", "#{이벤트명}" to "오픈 기념", "#{할인}" to "5,000원"),
            sent.captured.variables,
        )
    }

    @Test
    fun `일시적인 실패는 성공할 때까지 다시 보낸다`() {
        every { alimtalkSender.send(any()) } returnsMany listOf(retryableFailure, retryableFailure, AlimtalkSendResult.Success)

        couponNotificationService.notifyIssued(event)

        verify(exactly = 3) { alimtalkSender.send(any()) }
    }

    @Test
    fun `계속 실패해도 최대 시도 횟수까지만 보낸다`() {
        every { alimtalkSender.send(any()) } returns retryableFailure

        couponNotificationService.notifyIssued(event)

        verify(exactly = 3) { alimtalkSender.send(any()) }
    }

    @Test
    fun `다시 보내도 소용없는 실패는 재시도하지 않는다`() {
        every { alimtalkSender.send(any()) } returns AlimtalkSendResult.Failure(reason = "잘못된 수신번호", retryable = false)

        couponNotificationService.notifyIssued(event)

        verify(exactly = 1) { alimtalkSender.send(any()) }
    }

    @Test
    fun `휴대폰 번호가 없는 회원에게는 보내지 않는다`() {
        every { memberRepository.findById(100L) } returns Optional.of(member(phoneNumber = null))

        couponNotificationService.notifyIssued(event)

        verify(exactly = 0) { alimtalkSender.send(any()) }
    }

    private fun member(phoneNumber: String?) = Member(email = "user@test.com", password = "encoded", nickname = "로지", phoneNumber = phoneNumber)
}
