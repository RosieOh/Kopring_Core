package com.site.domain.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.site.domain.coupon.event.CouponIssuedEvent
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.coupon.repository.IssuedCouponRepository
import com.site.domain.coupon.service.CouponIssueService
import com.site.domain.member.entity.Member
import com.site.domain.member.repository.MemberRepository
import com.site.domain.notification.entity.NotificationOutbox
import com.site.domain.notification.entity.NotificationType
import com.site.domain.notification.entity.OutboxStatus
import com.site.domain.notification.port.AlimtalkSendResult
import com.site.domain.notification.repository.NotificationOutboxRepository
import com.site.domain.notification.service.NotificationOutboxProcessor
import com.site.support.FakeAlimtalkSender
import com.site.support.IntegrationTest
import com.site.support.TestAuthHelper
import com.site.support.couponEventFixture
import com.site.support.runConcurrently
import com.site.support.serviceNow
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
class NotificationOutboxTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val testAuthHelper: TestAuthHelper,
    private val fakeAlimtalkSender: FakeAlimtalkSender,
    private val couponIssueService: CouponIssueService,
    private val outboxProcessor: NotificationOutboxProcessor,
    private val outboxRepository: NotificationOutboxRepository,
    private val transactionTemplate: TransactionTemplate,
    private val couponEventRepository: CouponEventRepository,
    private val issuedCouponRepository: IssuedCouponRepository,
    private val memberRepository: MemberRepository,
) {

    @BeforeEach
    fun setUp() {
        fakeAlimtalkSender.reset()
        // 앞선 테스트 클래스들이 쿠폰을 발급하며 남긴 Outbox 행을 지운다.
        // 스케줄러(processDue)는 오래된 순으로 집어 가므로, 남아 있으면 이 테스트의 행까지 차례가 오지 않는다.
        outboxRepository.deleteAllInBatch()
    }

    @AfterEach
    fun tearDown() {
        fakeAlimtalkSender.reset()
        outboxRepository.deleteAllInBatch()
        issuedCouponRepository.deleteAllInBatch()
        couponEventRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
    }

    @Test
    fun `쿠폰이 발급되면 같은 트랜잭션에서 Outbox 에 기록되고, 커밋 후 바로 발송된다`() {
        val token = testAuthHelper.createMemberAndLogin(email = "user@test.com", phoneNumber = "01012345678")
        val couponEventId = couponEventRepository.save(couponEventFixture(name = "오픈 기념 5천원")).id

        issue(couponEventId, token).andExpect { status { isCreated() } }

        await atMost Duration.ofSeconds(5) untilAsserted {
            assertEquals(OutboxStatus.SENT, outboxRepository.findAll().single().status)
        }
        val message = fakeAlimtalkSender.sentMessages.single()
        assertEquals("01012345678", message.to)
        assertEquals("오픈 기념 5천원", message.variables["#{이벤트명}"])
    }

    @Test
    fun `발급 트랜잭션이 롤백되면 Outbox 기록도 남지 않고 알림도 나가지 않는다`() {
        val memberId = saveMember(phoneNumber = "01012345678").id
        val couponEventId = couponEventRepository.save(couponEventFixture()).id

        // 바깥 트랜잭션에 합류해서 발급(+ Outbox 기록)까지 한 뒤, 바깥 트랜잭션을 롤백시킨다
        transactionTemplate.executeWithoutResult { status ->
            couponIssueService.issueWithAtomicUpdate(couponEventId, memberId)
            status.setRollbackOnly()
        }

        assertEquals(0L, outboxRepository.count())
        await.during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).untilAsserted {
            assertEquals(0, fakeAlimtalkSender.attempts.get())
        }
    }

    @Test
    fun `커밋 직후 서버가 죽어 빠른 발송이 사라져도 스케줄러가 남은 알림을 보낸다`() {
        // 발급은 커밋됐지만 빠른 경로가 실행되기 전에 서버가 죽은 상황: PENDING 기록만 남아 있다
        val outboxId = savePendingOutbox(memberId = saveMember(phoneNumber = "01012345678").id).id

        outboxProcessor.processDue()

        assertEquals(OutboxStatus.SENT, outboxRepository.findById(outboxId).get().status)
        assertEquals(1, fakeAlimtalkSender.sentMessages.size)
    }

    @Test
    fun `일시적 실패는 다음 차례에 다시 보내고, 최대 횟수(3회)를 넘으면 FAILED 로 남긴다`() {
        fakeAlimtalkSender.failure = AlimtalkSendResult.Failure(reason = "timeout", retryable = true)
        val outboxId = savePendingOutbox(memberId = saveMember(phoneNumber = "01012345678").id).id

        repeat(2) { outboxProcessor.processDue() }
        outboxRepository.findById(outboxId).get().let {
            assertEquals(OutboxStatus.PENDING, it.status)
            assertEquals(2, it.attemptCount)
        }

        repeat(3) { outboxProcessor.processDue() } // 세 번째 시도에서 FAILED, 그 뒤로는 집어 가지 않는다

        val outbox = outboxRepository.findById(outboxId).get()
        assertEquals(OutboxStatus.FAILED, outbox.status)
        assertEquals(3, outbox.attemptCount)
        assertEquals("timeout", outbox.lastError)
        assertEquals(3, fakeAlimtalkSender.attempts.get())
    }

    @Test
    fun `빠른 경로와 스케줄러가 동시에 같은 건을 집어도 한 번만 보낸다`() {
        fakeAlimtalkSender.delay = Duration.ofMillis(200)
        val outboxId = savePendingOutbox(memberId = saveMember(phoneNumber = "01012345678").id).id

        runConcurrently(taskCount = 10) { outboxProcessor.process(outboxId) }

        assertEquals(1, fakeAlimtalkSender.attempts.get())
        assertEquals(OutboxStatus.SENT, outboxRepository.findById(outboxId).get().status)
    }

    @Test
    fun `휴대폰 번호가 없는 회원의 알림은 SKIPPED 로 끝낸다`() {
        val outboxId = savePendingOutbox(memberId = saveMember(phoneNumber = null).id).id

        outboxProcessor.processDue()

        assertEquals(OutboxStatus.SKIPPED, outboxRepository.findById(outboxId).get().status)
        assertEquals(0, fakeAlimtalkSender.attempts.get())
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

    private fun saveMember(phoneNumber: String?): Member =
        memberRepository.save(Member(email = "user@test.com", password = "encoded", nickname = "tester", phoneNumber = phoneNumber))

    private fun savePendingOutbox(memberId: Long): NotificationOutbox {
        val couponEventId = couponEventRepository.save(couponEventFixture()).id
        val event = CouponIssuedEvent(issuedCouponId = 1L, couponEventId = couponEventId, memberId = memberId)
        return outboxRepository.save(
            NotificationOutbox(
                type = NotificationType.COUPON_ISSUED,
                payload = objectMapper.writeValueAsString(event),
                nextAttemptAt = serviceNow(),
            ),
        )
    }

    private fun issue(couponEventId: Long, token: String) =
        mockMvc.post("/api/coupon-events/$couponEventId/issue") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }
}
