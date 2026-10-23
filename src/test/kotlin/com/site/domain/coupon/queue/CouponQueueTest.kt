package com.site.domain.coupon.queue

import com.site.domain.coupon.queue.CouponQueueResult.ISSUED
import com.site.domain.coupon.queue.CouponQueueResult.SOLD_OUT
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.coupon.repository.IssuedCouponRepository
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
import java.util.concurrent.ConcurrentHashMap

@IntegrationTest
class CouponQueueTest @Autowired constructor(
    private val couponQueueService: CouponQueueService,
    private val couponQueueWorker: CouponQueueWorker,
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
    fun `시작 전 이벤트에는 줄을 설 수 없다`() {
        val couponEventId = couponEventRepository.save(
            couponEventFixture(startAt = serviceNow().plusDays(1), endAt = serviceNow().plusDays(2)),
        ).id

        val exception = assertThrows<ServiceException> { couponQueueService.enqueue(couponEventId, 1L) }

        assertEquals(ErrorCode.COUPON_EVENT_NOT_STARTED, exception.errorCode)
    }

    private fun ongoingCouponEvent(totalQuantity: Int): Long = couponEventRepository.save(couponEventFixture(totalQuantity = totalQuantity)).id
}
