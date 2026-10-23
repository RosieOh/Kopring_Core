package com.site.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import com.site.domain.coupon.entity.IssuedCoupon
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.coupon.repository.IssuedCouponRepository
import com.site.domain.coupon.service.CouponWalletService
import com.site.domain.member.repository.MemberRepository
import com.site.domain.notification.repository.NotificationOutboxRepository
import com.site.independent.error.exception.ServiceException
import com.site.support.IntegrationTest
import com.site.support.TestAuthHelper
import com.site.support.couponEventFixture
import com.site.support.runConcurrently
import com.site.support.serviceNow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@IntegrationTest
class MyCouponApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val testAuthHelper: TestAuthHelper,
    private val couponWalletService: CouponWalletService,
    private val couponEventRepository: CouponEventRepository,
    private val issuedCouponRepository: IssuedCouponRepository,
    private val memberRepository: MemberRepository,
    private val outboxRepository: NotificationOutboxRepository,
) {

    private lateinit var token: String
    private var memberId: Long = 0L

    @BeforeEach
    fun setUp() {
        token = testAuthHelper.createMemberAndLogin(email = "user@test.com")
        memberId = requireNotNull(memberRepository.findByEmail("user@test.com")).id
    }

    @AfterEach
    fun tearDown() {
        outboxRepository.deleteAllInBatch()
        issuedCouponRepository.deleteAllInBatch()
        couponEventRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
    }

    @Test
    fun `이벤트 목록에는 진행 중·예정 이벤트만 시작이 빠른 순으로 나온다`() {
        couponEventRepository.save(couponEventFixture(name = "진행 중", startAt = serviceNow().minusDays(1)))
        couponEventRepository.save(couponEventFixture(name = "예정", startAt = serviceNow().plusDays(1), endAt = serviceNow().plusDays(2)))
        couponEventRepository.save(couponEventFixture(name = "종료", startAt = serviceNow().minusDays(3), endAt = serviceNow().minusDays(2)))

        mockMvc.get("/api/coupon-events") { bearer() }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.totalElements") { value(2) }
                jsonPath("$.data.content[0].name") { value("진행 중") }
                jsonPath("$.data.content[0].status") { value("ONGOING") }
                jsonPath("$.data.content[1].name") { value("예정") }
                jsonPath("$.data.content[1].discountText") { value("5,000원") }
            }
    }

    @Test
    fun `발급받은 쿠폰은 이벤트 정보와 만료 시각과 함께 내 쿠폰 목록에 나온다`() {
        val couponEventId = couponEventRepository.save(couponEventFixture(name = "오픈 기념")).id
        mockMvc.post("/api/coupon-events/$couponEventId/issue") { bearer() }.andExpect { status { isCreated() } }

        mockMvc.get("/api/members/me/coupons") { bearer() }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.content[0].eventName") { value("오픈 기념") }
                jsonPath("$.data.content[0].status") { value("AVAILABLE") }
                jsonPath("$.data.content[0].expiresAt") { exists() }
            }
    }

    @Test
    fun `쿠폰을 쓰면 할인액과 결제 금액을 돌려주고, 두 번은 쓸 수 없다`() {
        val couponId = issueTo(memberId)

        use(couponId, orderAmount = 20_000)
            .andExpect {
                status { isOk() }
                jsonPath("$.data.discountAmount") { value(5_000) }
                jsonPath("$.data.paymentAmount") { value(15_000) }
            }
        use(couponId, orderAmount = 20_000)
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("ALREADY_USED_COUPON") }
            }
    }

    @Test
    fun `남의 쿠폰은 없는 쿠폰으로 응답한다`() {
        val othersCouponId = issueTo(memberId = memberId + 1_000)

        use(othersCouponId, orderAmount = 20_000)
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("ISSUED_COUPON_NOT_FOUND") }
            }
    }

    @Test
    fun `유효기간이 지난 쿠폰은 쓸 수 없다`() {
        val couponId = issueTo(memberId, expiresAt = serviceNow().minusMinutes(1))

        use(couponId, orderAmount = 20_000)
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("EXPIRED_COUPON") }
            }
    }

    @Test
    fun `같은 쿠폰으로 동시에 10번 요청해도 한 번만 사용된다 (낙관적 락)`() {
        val couponId = issueTo(memberId)
        val successCount = AtomicInteger()
        val failReasons = ConcurrentHashMap<String, AtomicInteger>()

        runConcurrently(taskCount = 10) {
            try {
                couponWalletService.use(memberId, couponId, orderAmount = 20_000)
                successCount.incrementAndGet()
            } catch (e: ServiceException) {
                failReasons.computeIfAbsent(e.errorCode.name) { AtomicInteger() }.incrementAndGet()
            }
        }

        assertEquals(1, successCount.get())
        assertEquals(mapOf("ALREADY_USED_COUPON" to 9), failReasons.mapValues { it.value.get() })
        assertEquals(5_000, issuedCouponRepository.findById(couponId).get().discountAmount)
    }

    private fun issueTo(memberId: Long, expiresAt: LocalDateTime = serviceNow().plusDays(30)): Long {
        val couponEventId = couponEventRepository.save(couponEventFixture()).id
        return issuedCouponRepository.save(IssuedCoupon(couponEventId = couponEventId, memberId = memberId, expiresAt = expiresAt)).id
    }

    private fun use(couponId: Long, orderAmount: Int) =
        mockMvc.post("/api/members/me/coupons/$couponId/use") {
            bearer()
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("orderAmount" to orderAmount))
        }

    private fun MockHttpServletRequestDsl.bearer() {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
