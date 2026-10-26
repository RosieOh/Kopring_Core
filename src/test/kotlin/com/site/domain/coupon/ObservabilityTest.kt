package com.site.domain.coupon

import com.site.domain.coupon.entity.CouponIssueMode
import com.site.domain.coupon.queue.CouponQueueMetrics
import com.site.domain.coupon.queue.CouponQueueService
import com.site.domain.coupon.queue.CouponQueueWorker
import com.site.domain.coupon.queue.port.CouponQueueStore
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.coupon.repository.IssuedCouponRepository
import com.site.domain.coupon.service.CouponIssueFacade
import com.site.domain.coupon.service.CouponIssueMetrics
import com.site.domain.notification.repository.NotificationOutboxRepository
import com.site.independent.error.exception.ServiceException
import com.site.support.IntegrationTest
import com.site.support.couponEventFixture
import com.site.support.flushDb
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * 부하 테스트와 운영에서 볼 지표가 제대로 쌓이는지 확인한다.
 * 지표는 컨텍스트를 공유하는 다른 테스트에서도 쌓이므로, 절댓값이 아니라 "전후 차이"로 확인한다.
 */
@IntegrationTest
class ObservabilityTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val meterRegistry: MeterRegistry,
    private val couponIssueFacade: CouponIssueFacade,
    private val couponQueueService: CouponQueueService,
    private val couponQueueWorker: CouponQueueWorker,
    private val couponQueueStore: CouponQueueStore,
    private val couponQueueMetrics: CouponQueueMetrics,
    private val couponEventRepository: CouponEventRepository,
    private val issuedCouponRepository: IssuedCouponRepository,
    private val outboxRepository: NotificationOutboxRepository,
    private val redisTemplate: StringRedisTemplate,
) {

    @AfterEach
    fun tearDown() {
        outboxRepository.deleteAllInBatch()
        issuedCouponRepository.deleteAllInBatch()
        couponEventRepository.deleteAllInBatch()
        redisTemplate.flushDb()
    }

    @Test
    fun `즉시 발급 결과를 mode·result 태그로 세고, 락 대기 시간을 기록한다`() {
        val couponEventId = couponEventRepository.save(couponEventFixture(totalQuantity = 1)).id
        val issuedBefore = issueCount("direct", "issued")
        val soldOutBefore = issueCount("direct", "sold_out")
        val lockWaitsBefore = lockWaitCount()

        couponIssueFacade.issue(couponEventId, memberId = 1L)
        assertThrows<ServiceException> { couponIssueFacade.issue(couponEventId, memberId = 2L) }

        assertEquals(issuedBefore + 1, issueCount("direct", "issued"))
        assertEquals(soldOutBefore + 1, issueCount("direct", "sold_out"))
        // 두 번째 요청은 사전 검사에서 거절돼 락까지 가지 않는다
        assertEquals(lockWaitsBefore + 1, lockWaitCount())
    }

    @Test
    fun `대기열 결과는 마감 후 한꺼번에 정리된 인원까지 센다`() {
        val couponEventId = couponEventRepository.save(couponEventFixture(totalQuantity = 1, issueMode = CouponIssueMode.QUEUE)).id
        (1L..3L).forEach { couponQueueService.enqueue(couponEventId, it) }
        val issuedBefore = issueCount("queue", "issued")
        val soldOutBefore = issueCount("queue", "sold_out")

        couponQueueWorker.processAll()

        assertEquals(issuedBefore + 1, issueCount("queue", "issued"))
        assertEquals(soldOutBefore + 2, issueCount("queue", "sold_out"))
    }

    @Test
    fun `대기열에 사람이 있으면 이벤트별 인원이 게이지로 보이고, 비고 나면 지표에서 빠진다`() {
        val couponEventId = couponEventRepository.save(couponEventFixture(issueMode = CouponIssueMode.QUEUE)).id
        (1L..2L).forEach { couponQueueService.enqueue(couponEventId, it) }

        couponQueueMetrics.refresh(couponQueueStore.activeEventIds())
        assertEquals(2.0, queueSize(couponEventId, "waiting"))

        couponQueueWorker.processAll() // 2명을 발급한 주기: 대기 0명
        assertEquals(0.0, queueSize(couponEventId, "waiting"))

        couponQueueWorker.processAll() // 다음 주기: 빈 대기열이 순회 대상에서 빠지면서 지표에서도 사라진다
        assertEquals(null, queueSize(couponEventId, "waiting"))
    }

    @Test
    fun `Prometheus 수집 경로로 커스텀 지표가 나간다`() {
        val couponEventId = couponEventRepository.save(couponEventFixture()).id
        couponIssueFacade.issue(couponEventId, memberId = 1L)
        mockMvc.get("/actuator/health") // HTTP 요청 지표는 요청이 끝나야 생긴다

        val body = mockMvc.get("/actuator/prometheus")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        listOf("coupon_issue_requests_total", "lock_wait_seconds_bucket", "notification_outbox_pending", "http_server_requests_seconds_bucket")
            .forEach { name -> assertTrue(body.contains(name)) { "$name 지표가 없습니다" } }
    }

    private fun issueCount(mode: String, result: String): Double =
        meterRegistry.find(CouponIssueMetrics.NAME).tags("mode", mode, "result", result).counter()?.count() ?: 0.0

    private fun lockWaitCount(): Long = meterRegistry.find("lock.wait").tag("result", "acquired").timer()?.count() ?: 0L

    private fun queueSize(couponEventId: Long, state: String): Double? =
        meterRegistry.find(CouponQueueMetrics.NAME).tags("event_id", couponEventId.toString(), "state", state).gauge()?.value()
}
