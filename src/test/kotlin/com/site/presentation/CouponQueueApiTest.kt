package com.site.presentation

import com.site.domain.coupon.entity.CouponIssueMode
import com.site.domain.coupon.queue.CouponQueueWorker
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.coupon.repository.IssuedCouponRepository
import com.site.domain.member.repository.MemberRepository
import com.site.presentation.coupon.stream.CouponQueueStatusStreams
import com.site.support.IntegrationTest
import com.site.support.TestAuthHelper
import com.site.support.couponEventFixture
import com.site.support.flushDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@IntegrationTest
class CouponQueueApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val testAuthHelper: TestAuthHelper,
    private val couponQueueWorker: CouponQueueWorker,
    private val queueStreams: CouponQueueStatusStreams,
    private val couponEventRepository: CouponEventRepository,
    private val issuedCouponRepository: IssuedCouponRepository,
    private val memberRepository: MemberRepository,
    private val redisTemplate: StringRedisTemplate,
) {

    private lateinit var token: String

    @BeforeEach
    fun setUp() {
        token = testAuthHelper.createMemberAndLogin(email = "user@test.com")
    }

    @AfterEach
    fun tearDown() {
        issuedCouponRepository.deleteAllInBatch()
        couponEventRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
        redisTemplate.flushDb()
    }

    @Test
    fun `대기열에 등록하면 202와 순번을 받고, 워커가 처리하면 발급 완료 상태가 된다`() {
        val couponEventId = couponEventRepository.save(couponEventFixture(issueMode = CouponIssueMode.QUEUE)).id

        mockMvc.post("/api/coupon-events/$couponEventId/queue") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.data.state") { value("WAITING") }
            jsonPath("$.data.position") { value(1) }
            jsonPath("$.data.retryAfterMillis") { value(300) }
        }

        couponQueueWorker.processAll()

        getMyStatus(couponEventId).andExpect {
            status { isOk() }
            jsonPath("$.data.state") { value("DONE") }
            jsonPath("$.data.result") { value("ISSUED") }
        }
    }

    @Test
    fun `줄을 서지 않았으면 NOT_IN_QUEUE`() {
        val couponEventId = couponEventRepository.save(couponEventFixture(issueMode = CouponIssueMode.QUEUE)).id

        getMyStatus(couponEventId).andExpect {
            status { isOk() }
            jsonPath("$.data.state") { value("NOT_IN_QUEUE") }
        }
    }

    @Test
    fun `즉시 발급 이벤트에 줄을 서려 하면 400 DIRECT_ONLY_COUPON_EVENT`() {
        val couponEventId = couponEventRepository.save(couponEventFixture(issueMode = CouponIssueMode.DIRECT)).id

        mockMvc.post("/api/coupon-events/$couponEventId/queue") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("DIRECT_ONLY_COUPON_EVENT") }
        }
    }

    @Test
    fun `SSE 로 연결하면 지금 상태를 보내고, 워커가 발급하면 결과를 보낸 뒤 연결을 닫는다`() {
        val couponEventId = couponEventRepository.save(couponEventFixture(issueMode = CouponIssueMode.QUEUE)).id
        mockMvc.post("/api/coupon-events/$couponEventId/queue") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }

        val stream = mockMvc.get("/api/coupon-events/$couponEventId/queue/me/stream") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect { request { asyncStarted() } }.andReturn()
        assertTrue(stream.response.contentAsString.contains("\"state\":\"WAITING\"")) { stream.response.contentAsString }

        couponQueueWorker.processAll()
        queueStreams.pushAll() // 스케줄러 대신 직접 부른다

        mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk)
        val events = stream.response.contentAsString
        assertTrue(events.contains("event:status")) { events }
        assertTrue(events.contains("\"state\":\"DONE\"") && events.contains("\"result\":\"ISSUED\"")) { events }
        assertEquals(0, queueStreams.connectionCount())
    }

    @Test
    fun `이미 결과가 나온 회원은 SSE 로 연결하자마자 결과를 받고 연결이 닫힌다`() {
        val couponEventId = couponEventRepository.save(couponEventFixture(issueMode = CouponIssueMode.QUEUE)).id
        mockMvc.post("/api/coupon-events/$couponEventId/queue") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }
        couponQueueWorker.processAll()

        val stream = mockMvc.get("/api/coupon-events/$couponEventId/queue/me/stream") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andReturn()

        mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk)
        assertTrue(stream.response.contentAsString.contains("\"result\":\"ISSUED\"")) { stream.response.contentAsString }
        assertEquals(0, queueStreams.connectionCount())
    }

    private fun getMyStatus(couponEventId: Long) =
        mockMvc.get("/api/coupon-events/$couponEventId/queue/me") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }
}
