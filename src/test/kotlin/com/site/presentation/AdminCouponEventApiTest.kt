package com.site.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.member.entity.MemberRole
import com.site.domain.member.repository.MemberRepository
import com.site.support.IntegrationTest
import com.site.support.TestAuthHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@IntegrationTest
class AdminCouponEventApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val memberRepository: MemberRepository,
    private val couponEventRepository: CouponEventRepository,
    private val testAuthHelper: TestAuthHelper,
) {

    private val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
    private lateinit var adminToken: String

    @BeforeEach
    fun setUp() {
        adminToken = testAuthHelper.createMemberAndLogin(email = "admin@test.com", role = MemberRole.ADMIN)
    }

    @AfterEach
    fun tearDown() {
        couponEventRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
    }

    @Test
    fun `관리자가 쿠폰 이벤트를 등록하면 시작 전 상태로 만들어진다`() {
        createCouponEvent(couponEventBody())
            .andExpect {
                status { isCreated() }
                jsonPath("$.code") { value("CREATE_COUPON_EVENT_SUCCESS") }
                jsonPath("$.data.status") { value("UPCOMING") }
                jsonPath("$.data.discountType") { value("FIXED_AMOUNT") }
                jsonPath("$.data.totalQuantity") { value(100) }
                jsonPath("$.data.remainingQuantity") { value(100) }
            }
    }

    @Test
    fun `시작 시각이 과거여도 종료 전이면 바로 진행 중 이벤트로 등록된다`() {
        createCouponEvent(couponEventBody(startAt = now.minusHours(1)))
            .andExpect {
                status { isCreated() }
                jsonPath("$.data.status") { value("ONGOING") }
            }
    }

    @Test
    fun `이미 끝난 기간으로는 등록할 수 없다`() {
        createCouponEvent(couponEventBody(startAt = now.minusDays(2), endAt = now.minusDays(1)))
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_COUPON_EVENT_PERIOD") }
            }
    }

    @Test
    fun `정률 할인이 100퍼센트를 넘으면 400`() {
        createCouponEvent(couponEventBody(discountType = "PERCENTAGE", discountValue = 150))
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_DISCOUNT_VALUE") }
            }
    }

    @Test
    fun `일반 회원은 쿠폰 이벤트를 등록할 수 없다`() {
        val userToken = testAuthHelper.createMemberAndLogin(email = "user@test.com", role = MemberRole.USER)

        createCouponEvent(couponEventBody(), token = userToken)
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("ROLE_FORBIDDEN") }
            }
    }

    @Test
    fun `목록은 최신 등록순으로 페이징된다`() {
        listOf("첫 번째", "두 번째", "세 번째").forEach { name ->
            createCouponEvent(couponEventBody(name = name))
        }

        mockMvc.get("/api/admin/coupon-events?page=0&size=2") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $adminToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.content.length()") { value(2) }
            jsonPath("$.data.content[0].name") { value("세 번째") }
            jsonPath("$.data.totalElements") { value(3) }
            jsonPath("$.data.hasNext") { value(true) }
        }
    }

    @Test
    fun `존재하지 않는 이벤트를 조회하면 404`() {
        getCouponEvent(999_999)
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("COUPON_EVENT_NOT_FOUND") }
            }
    }

    @Test
    fun `ID 자리에 숫자가 아닌 값이 오면 400`() {
        mockMvc.get("/api/admin/coupon-events/abc") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $adminToken")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_INPUT_VALUE") }
        }
    }

    @Test
    fun `시작 전 이벤트는 수정할 수 있다`() {
        val couponEventId = createCouponEventAndGetId(couponEventBody())

        val body = couponEventBody(name = "수정된 이벤트", discountType = "PERCENTAGE", discountValue = 10, totalQuantity = 30)
        updateCouponEvent(couponEventId, body)
            .andExpect {
                status { isOk() }
                jsonPath("$.data.name") { value("수정된 이벤트") }
            }

        // save() 없이도 dirty checking 으로 DB 에 반영됐는지 다시 조회해서 확인
        getCouponEvent(couponEventId)
            .andExpect {
                jsonPath("$.data.name") { value("수정된 이벤트") }
                jsonPath("$.data.discountType") { value("PERCENTAGE") }
                jsonPath("$.data.discountValue") { value(10) }
                jsonPath("$.data.totalQuantity") { value(30) }
            }
    }

    @Test
    fun `이미 시작된 이벤트는 수정할 수 없다`() {
        val couponEventId = createCouponEventAndGetId(couponEventBody(startAt = now.minusHours(1)))

        updateCouponEvent(couponEventId, couponEventBody(name = "수정된 이벤트"))
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("COUPON_EVENT_ALREADY_STARTED") }
            }
    }

    @Test
    fun `시작 전 이벤트는 삭제할 수 있다`() {
        val couponEventId = createCouponEventAndGetId(couponEventBody())

        deleteCouponEvent(couponEventId)
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("DELETE_COUPON_EVENT_SUCCESS") }
            }
        getCouponEvent(couponEventId)
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `이미 시작된 이벤트는 삭제할 수 없다`() {
        val couponEventId = createCouponEventAndGetId(couponEventBody(startAt = now.minusHours(1)))

        deleteCouponEvent(couponEventId)
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("COUPON_EVENT_ALREADY_STARTED") }
            }
    }

    private fun couponEventBody(
        name: String = "신규 가입 5천원 할인",
        discountType: String = "FIXED_AMOUNT",
        discountValue: Int = 5_000,
        totalQuantity: Int = 100,
        startAt: LocalDateTime = now.plusDays(1),
        endAt: LocalDateTime = now.plusDays(8),
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "name" to name,
            "discountType" to discountType,
            "discountValue" to discountValue,
            "totalQuantity" to totalQuantity,
            "startAt" to startAt,
            "endAt" to endAt,
        ),
    )

    private fun createCouponEvent(body: String, token: String = adminToken) =
        mockMvc.post("/api/admin/coupon-events") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun createCouponEventAndGetId(body: String): Long {
        val response = createCouponEvent(body)
            .andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        return objectMapper.readTree(response)["data"]["id"].asLong()
    }

    private fun getCouponEvent(couponEventId: Long) =
        mockMvc.get("/api/admin/coupon-events/$couponEventId") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $adminToken")
        }

    private fun updateCouponEvent(couponEventId: Long, body: String) =
        mockMvc.put("/api/admin/coupon-events/$couponEventId") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun deleteCouponEvent(couponEventId: Long) =
        mockMvc.delete("/api/admin/coupon-events/$couponEventId") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $adminToken")
        }
}
