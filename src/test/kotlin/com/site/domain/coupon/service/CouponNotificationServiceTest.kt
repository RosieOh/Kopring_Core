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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

class CouponNotificationServiceTest {

    private val memberRepository = mockk<MemberRepository>()
    private val couponEventRepository = mockk<CouponEventRepository>()
    private val alimtalkSender = mockk<AlimtalkSender>()

    private val couponNotificationService = CouponNotificationService(memberRepository, couponEventRepository, alimtalkSender)

    private val event = CouponIssuedEvent(issuedCouponId = 1L, couponEventId = 10L, memberId = 100L)

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

        val result = couponNotificationService.notifyIssued(event)

        assertEquals(NotificationResult.Sent, result)
        assertEquals("01012345678", sent.captured.to)
        assertEquals(AlimtalkTemplate.COUPON_ISSUED, sent.captured.template)
        assertEquals(
            mapOf("#{닉네임}" to "로지", "#{이벤트명}" to "오픈 기념", "#{할인}" to "5,000원"),
            sent.captured.variables,
        )
    }

    @Test
    fun `발송 실패는 재시도 가능 여부와 함께 돌려준다 (재시도는 Outbox 가 결정한다)`() {
        every { alimtalkSender.send(any()) } returns AlimtalkSendResult.Failure(reason = "timeout", retryable = true)

        val result = couponNotificationService.notifyIssued(event)

        assertEquals(NotificationResult.Failed(reason = "timeout", retryable = true), result)
        verify(exactly = 1) { alimtalkSender.send(any()) }
    }

    @Test
    fun `휴대폰 번호가 없는 회원에게는 보내지 않고 SKIPPED`() {
        every { memberRepository.findById(100L) } returns Optional.of(member(phoneNumber = null))

        val result = couponNotificationService.notifyIssued(event)

        assertEquals(NotificationResult.Skipped("휴대폰 번호가 없습니다"), result)
        verify(exactly = 0) { alimtalkSender.send(any()) }
    }

    private fun member(phoneNumber: String?) = Member(email = "user@test.com", password = "encoded", nickname = "로지", phoneNumber = phoneNumber)
}
