package com.site.presentation

import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.coupon.repository.IssuedCouponRepository
import com.site.domain.member.repository.MemberRepository
import com.site.support.IntegrationTest
import com.site.support.TestAuthHelper
import com.site.support.couponEventFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.LocalDateTime

@IntegrationTest
class CouponIssueApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val testAuthHelper: TestAuthHelper,
    private val couponEventRepository: CouponEventRepository,
    private val issuedCouponRepository: IssuedCouponRepository,
    private val memberRepository: MemberRepository,
) {

    private lateinit var userToken: String

    @BeforeEach
    fun setUp() {
        userToken = testAuthHelper.createMemberAndLogin(email = "user@test.com")
    }

    @AfterEach
    fun tearDown() {
        issuedCouponRepository.deleteAllInBatch()
        couponEventRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
    }

    @Test
    fun `진행 중인 이벤트에서 쿠폰을 발급받으면 남은 수량이 줄어든다`() {
        val couponEventId = couponEventRepository.save(couponEventFixture(totalQuantity = 100)).id

        issue(couponEventId)
            .andExpect {
                status { isCreated() }
                jsonPath("$.code") { value("ISSUE_COUPON_SUCCESS") }
                jsonPath("$.data.couponEventId") { value(couponEventId) }
            }

        val couponEvent = requireNotNull(couponEventRepository.findByIdOrNull(couponEventId))
        assertEquals(99, couponEvent.remainingQuantity)
    }

    @Test
    fun `같은 이벤트에서 두 번 발급받을 수 없다`() {
        val couponEventId = couponEventRepository.save(couponEventFixture()).id
        issue(couponEventId).andExpect { status { isCreated() } }

        issue(couponEventId)
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("ALREADY_ISSUED_COUPON") }
            }
    }

    @Test
    fun `수량이 모두 소진되면 COUPON_SOLD_OUT`() {
        val couponEventId = couponEventRepository.save(couponEventFixture(totalQuantity = 1)).id
        val otherToken = testAuthHelper.createMemberAndLogin(email = "other@test.com")
        issue(couponEventId, otherToken).andExpect { status { isCreated() } }

        issue(couponEventId)
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("COUPON_SOLD_OUT") }
            }
    }

    @Test
    fun `시작 전 이벤트는 발급받을 수 없다`() {
        val couponEventId = couponEventRepository.save(
            couponEventFixture(startAt = LocalDateTime.now().plusDays(1), endAt = LocalDateTime.now().plusDays(2)),
        ).id

        issue(couponEventId)
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("COUPON_EVENT_NOT_STARTED") }
            }
    }

    @Test
    fun `존재하지 않는 이벤트는 404`() {
        issue(999_999)
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("COUPON_EVENT_NOT_FOUND") }
            }
    }

    @Test
    fun `로그인하지 않으면 발급받을 수 없다`() {
        val couponEventId = couponEventRepository.save(couponEventFixture()).id

        mockMvc.post("/api/coupon-events/$couponEventId/issue")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("EMPTY_AUTHENTICATION") }
            }
    }

    private fun issue(couponEventId: Long, token: String = userToken) =
        mockMvc.post("/api/coupon-events/$couponEventId/issue") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }
}
