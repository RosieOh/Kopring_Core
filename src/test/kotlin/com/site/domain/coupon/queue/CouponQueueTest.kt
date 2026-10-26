package com.site.domain.coupon.queue

import com.site.domain.coupon.entity.CouponIssueMode
import com.site.domain.coupon.queue.CouponQueueResult.ISSUED
import com.site.domain.coupon.queue.CouponQueueResult.SOLD_OUT
import com.site.domain.coupon.queue.port.CouponQueueStore
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.coupon.repository.IssuedCouponRepository
import com.site.domain.coupon.service.CouponIssueService
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import com.site.support.IntegrationTest
import com.site.support.couponEventFixture
import com.site.support.flushDb
import com.site.support.runConcurrently
import com.site.support.serviceNow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

@IntegrationTest
class CouponQueueTest @Autowired constructor(
    private val couponQueueService: CouponQueueService,
    private val couponQueueWorker: CouponQueueWorker,
    private val couponQueueStore: CouponQueueStore,
    private val couponIssueService: CouponIssueService,
    private val clock: Clock,
    private val couponEventRepository: CouponEventRepository,
    private val issuedCouponRepository: IssuedCouponRepository,
    private val redisTemplate: StringRedisTemplate,
) {

    @AfterEach
    fun tearDown() {
        issuedCouponRepository.deleteAllInBatch()
        couponEventRepository.deleteAllInBatch()
        redisTemplate.flushDb()
    }

    @Test
    fun `들어온 순서대로 순번이 매겨지고, 다시 들어와도 순번이 바뀌지 않는다`() {
        val couponEventId = ongoingCouponEvent(totalQuantity = 10)

        assertEquals(CouponQueueStatus.Waiting(1), couponQueueService.enqueue(couponEventId, 101L))
        assertEquals(CouponQueueStatus.Waiting(2), couponQueueService.enqueue(couponEventId, 102L))
        assertEquals(CouponQueueStatus.Waiting(3), couponQueueService.enqueue(couponEventId, 103L))

        assertEquals(CouponQueueStatus.Waiting(2), couponQueueService.enqueue(couponEventId, 102L))
        assertEquals(CouponQueueStatus.Waiting(2), couponQueueService.getStatus(couponEventId, 102L))
        assertEquals(CouponQueueStatus.NotInQueue, couponQueueService.getStatus(couponEventId, 999L))
    }

    @Test
    fun `워커는 앞사람부터 발급하고, 수량이 떨어지면 나머지를 SOLD_OUT 으로 정리한 뒤 대기열을 닫는다`() {
        val couponEventId = ongoingCouponEvent(totalQuantity = 3)
        val memberIds = (1L..5L).toList()
        memberIds.forEach { couponQueueService.enqueue(couponEventId, it) }

        couponQueueWorker.processAll()

        assertEquals(
            listOf(ISSUED, ISSUED, ISSUED, SOLD_OUT, SOLD_OUT).map { CouponQueueStatus.Done(it) },
            memberIds.map { couponQueueService.getStatus(couponEventId, it) },
        )
        assertEquals(3L, issuedCouponRepository.countByCouponEventId(couponEventId))

        // 닫힌 뒤에는 새로 줄을 설 수 없고, 이미 처리된 사람은 다시 와도 자기 결과를 돌려받는다
        val exception = assertThrows<ServiceException> { couponQueueService.enqueue(couponEventId, 6L) }
        assertEquals(ErrorCode.COUPON_SOLD_OUT, exception.errorCode)
        assertEquals(CouponQueueStatus.Done(ISSUED), couponQueueService.enqueue(couponEventId, 1L))
    }

    @Test
    fun `300명이 동시에 줄을 서도 순번이 겹치거나 빠지지 않고, 앞선 100명만 발급된다`() {
        val couponEventId = ongoingCouponEvent(totalQuantity = 100)
        val positions = ConcurrentHashMap<Long, Long>()

        runConcurrently(taskCount = 300) { index ->
            val memberId = index + 1L
            val status = couponQueueService.enqueue(couponEventId, memberId) as CouponQueueStatus.Waiting
            positions[memberId] = status.position
        }

        // Lua 스크립트가 원자적이므로 순번은 1~300 이 정확히 하나씩 나온다
        assertEquals((1L..300L).toSet(), positions.values.toSet())

        // 배치 50명씩: 두 번에 100장이 나가고, 세 번째 배치에서 소진을 만나 나머지를 정리한다
        repeat(5) { couponQueueWorker.processAll() }

        val issuedPositions = positions
            .filterKeys { memberId -> couponQueueService.getStatus(couponEventId, memberId) == CouponQueueStatus.Done(ISSUED) }
            .values
        assertEquals(100, issuedPositions.size)
        assertTrue(issuedPositions.all { it <= 100 }) { "앞선 100명이 아닌 회원이 발급받았습니다" }
        assertEquals(100L, issuedCouponRepository.countByCouponEventId(couponEventId))
    }

    @Test
    fun `일괄 발급은 이미 받은 회원은 그대로 두고, 남은 수량만큼 앞사람부터 발급한 뒤 나머지는 소진으로 돌려준다`() {
        val couponEventId = ongoingCouponEvent(totalQuantity = 3)
        couponIssueService.issueWithAtomicUpdate(couponEventId, 1L) // 1번은 이전 처리에서 이미 받았다

        val result = couponIssueService.issueQueuedBatch(couponEventId, listOf(1L, 2L, 3L, 4L, 5L))

        assertEquals(listOf(1L, 2L, 3L), result.issued)
        assertEquals(listOf(4L, 5L), result.rejected)
        assertEquals(ErrorCode.COUPON_SOLD_OUT, result.rejectReason)
        assertEquals(3, requireNotNull(couponEventRepository.findById(couponEventId).orElse(null)).issuedQuantity)
        assertEquals(3L, issuedCouponRepository.countByCouponEventId(couponEventId))
    }

    @Test
    fun `줄이 남아 있으면 워커 한 번 호출로 꽉 찬 배치를 쉬지 않고 이어서 처리한다`() {
        val couponEventId = ongoingCouponEvent(totalQuantity = 200)
        (1L..150L).forEach { couponQueueService.enqueue(couponEventId, it) } // 배치 50명 × 3

        couponQueueWorker.processAll()

        assertEquals(150L, issuedCouponRepository.countByCouponEventId(couponEventId))
        assertEquals(CouponQueueStatus.Done(ISSUED), couponQueueService.getStatus(couponEventId, 150L))
    }

    @Test
    fun `워커 여러 대가 동시에 처리해도 수량을 넘지 않고, 모두가 결과를 받는다`() {
        val couponEventId = ongoingCouponEvent(totalQuantity = 100)
        val memberIds = (1L..300L).toList()
        memberIds.forEach { couponQueueService.enqueue(couponEventId, it) }

        // 서버 4대에서 워커가 동시에 도는 상황
        runConcurrently(taskCount = 4, threadCount = 4) { repeat(10) { couponQueueWorker.processAll() } }

        assertEquals(100L, issuedCouponRepository.countByCouponEventId(couponEventId))
        val results = memberIds.map { couponQueueService.getStatus(couponEventId, it) }.groupingBy { it }.eachCount()
        assertEquals(mapOf(CouponQueueStatus.Done(ISSUED) to 100, CouponQueueStatus.Done(SOLD_OUT) to 200), results)
    }

    @Test
    fun `시작 전 이벤트에는 줄을 설 수 없다`() {
        val couponEventId = couponEventRepository.save(
            couponEventFixture(startAt = serviceNow().plusDays(1), endAt = serviceNow().plusDays(2), issueMode = CouponIssueMode.QUEUE),
        ).id

        val exception = assertThrows<ServiceException> { couponQueueService.enqueue(couponEventId, 1L) }

        assertEquals(ErrorCode.COUPON_EVENT_NOT_STARTED, exception.errorCode)
    }

    @Test
    fun `처리하던 워커가 죽어도 lease 가 지나면 다음 워커가 이어서 처리하고, 이미 발급된 회원은 두 번 발급하지 않는다`() {
        val couponEventId = ongoingCouponEvent(totalQuantity = 10)
        (1L..3L).forEach { couponQueueService.enqueue(couponEventId, it) }

        // 워커가 3명을 꺼낸 뒤 죽은 상황: lease 가 이미 지난 채로 "처리 중"에 남아 있다
        couponQueueStore.claimNext(couponEventId, count = 3, leaseUntilMillis = clock.millis() - 1)
        assertEquals(CouponQueueStatus.Processing, couponQueueService.getStatus(couponEventId, 1L))
        // 그중 1번은 DB 발급까지 끝내고 결과를 기록하기 직전에 죽었다
        couponIssueService.issueWithAtomicUpdate(couponEventId, 1L)

        couponQueueWorker.processAll()

        assertEquals(
            List(3) { CouponQueueStatus.Done(ISSUED) },
            (1L..3L).map { couponQueueService.getStatus(couponEventId, it) },
        )
        assertEquals(3L, issuedCouponRepository.countByCouponEventId(couponEventId))
    }

    @Test
    fun `lease 가 남은 처리 중 회원은 다른 워커가 가져가지 않고, 다시 줄을 서도 처리 중으로 안내한다`() {
        val couponEventId = ongoingCouponEvent(totalQuantity = 10)
        couponQueueService.enqueue(couponEventId, 1L)
        couponQueueStore.claimNext(couponEventId, count = 1, leaseUntilMillis = clock.millis() + 60_000)

        couponQueueWorker.processAll()

        assertEquals(CouponQueueStatus.Processing, couponQueueService.getStatus(couponEventId, 1L))
        assertEquals(CouponQueueStatus.Processing, couponQueueService.enqueue(couponEventId, 1L))
        assertEquals(0L, issuedCouponRepository.countByCouponEventId(couponEventId))
    }

    @Test
    fun `즉시 발급 이벤트에는 줄을 설 수 없다`() {
        val couponEventId = couponEventRepository.save(couponEventFixture(issueMode = CouponIssueMode.DIRECT)).id

        val exception = assertThrows<ServiceException> { couponQueueService.enqueue(couponEventId, 1L) }

        assertEquals(ErrorCode.DIRECT_ONLY_COUPON_EVENT, exception.errorCode)
    }

    private fun ongoingCouponEvent(totalQuantity: Int): Long =
        couponEventRepository.save(couponEventFixture(totalQuantity = totalQuantity, issueMode = CouponIssueMode.QUEUE)).id
}
