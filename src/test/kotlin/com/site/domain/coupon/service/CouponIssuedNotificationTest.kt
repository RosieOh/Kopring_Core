package com.site.domain.coupon.service

import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.coupon.repository.IssuedCouponRepository
import com.site.domain.member.repository.MemberRepository
import com.site.support.FakeAlimtalkSender
import com.site.support.IntegrationTest
import com.site.support.TestAuthHelper
import com.site.support.couponEventFixture
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import kotlin.system.measureTimeMillis

@IntegrationTest
class CouponIssuedNotificationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val testAuthHelper: TestAuthHelper,
    private val fakeAlimtalkSender: FakeAlimtalkSender,
    private val couponIssueService: CouponIssueService,
    private val transactionTemplate: TransactionTemplate,
    private val couponEventRepository: CouponEventRepository,
    private val issuedCouponRepository: IssuedCouponRepository,
    private val memberRepository: MemberRepository,
) {

    @BeforeEach
    fun setUp() {
        fakeAlimtalkSender.reset()
    }

    @AfterEach
    fun tearDown() {
        fakeAlimtalkSender.reset()
        issuedCouponRepository.deleteAllInBatch()
        couponEventRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
    }

    @Test
    fun `쿠폰이 발급되면 커밋 후 회원 휴대폰으로 알림톡이 발송된다`() {
        val token = testAuthHelper.createMemberAndLogin(email = "user@test.com", phoneNumber = "01012345678")
        val couponEventId = couponEventRepository.save(couponEventFixture(name = "오픈 기념 5천원")).id

        issue(couponEventId, token).andExpect { status { isCreated() } }

        await atMost Duration.ofSeconds(5) untilAsserted {
            val message = fakeAlimtalkSender.sentMessages.single()
            assertEquals("01012345678", message.to)
            assertEquals("오픈 기념 5천원", message.variables["#{이벤트명}"])
            assertEquals("5,000원", message.variables["#{할인}"])
        }
    }

    @Test
    fun `발급 트랜잭션이 롤백되면 알림톡이 나가지 않는다`() {
        testAuthHelper.createMemberAndLogin(email = "user@test.com", phoneNumber = "01012345678")
        val memberId = requireNotNull(memberRepository.findByEmail("user@test.com")).id
        val couponEventId = couponEventRepository.save(couponEventFixture()).id

        // 바깥 트랜잭션에 합류해서 발급(+ 이벤트 발행)까지 한 뒤, 바깥 트랜잭션을 롤백시킨다
        transactionTemplate.executeWithoutResult { status ->
            couponIssueService.issueWithAtomicUpdate(couponEventId, memberId)
            status.setRollbackOnly()
        }

        // 1초 동안 계속 "보낸 메시지 없음"이 유지되는지 확인한다
        await.during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).untilAsserted {
            assertTrue(fakeAlimtalkSender.sentMessages.isEmpty())
        }
        assertEquals(0L, issuedCouponRepository.countByCouponEventId(couponEventId))
    }

    @Test
    fun `알림 발송이 느려도 발급 API 는 기다리지 않고 바로 응답한다`() {
        val token = testAuthHelper.createMemberAndLogin(email = "user@test.com", phoneNumber = "01012345678")
        val couponEventId = couponEventRepository.save(couponEventFixture()).id
        fakeAlimtalkSender.delay = Duration.ofSeconds(2)

        val elapsedMillis = measureTimeMillis {
            issue(couponEventId, token).andExpect { status { isCreated() } }
        }

        assertTrue(elapsedMillis < 1_000) { "발급 응답이 알림 발송을 기다렸습니다: ${elapsedMillis}ms" }
        await atMost Duration.ofSeconds(5) untilAsserted {
            assertEquals(1, fakeAlimtalkSender.sentMessages.size)
        }
    }

    private fun issue(couponEventId: Long, token: String) =
        mockMvc.post("/api/coupon-events/$couponEventId/issue") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }
}
