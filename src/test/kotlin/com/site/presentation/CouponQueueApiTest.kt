package com.site.presentation

import com.site.domain.coupon.entity.CouponIssueMode
import com.site.domain.coupon.queue.CouponQueueWorker
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.coupon.repository.IssuedCouponRepository
import com.site.domain.member.repository.MemberRepository
import com.site.support.IntegrationTest
import com.site.support.TestAuthHelper
import com.site.support.couponEventFixture
import com.site.support.flushDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@IntegrationTest
class CouponQueueApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val testAuthHelper: TestAuthHelper,
    private val couponQueueWorker: CouponQueueWorker,
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

    private fun getMyStatus(couponEventId: Long) =
        mockMvc.get("/api/coupon-events/$couponEventId/queue/me") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }
}
