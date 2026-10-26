package com.site.domain.coupon.service

import com.site.domain.coupon.entity.CouponIssueMode
import com.site.domain.coupon.entity.IssuedCoupon
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.coupon.repository.IssuedCouponRepository
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import com.site.support.IntegrationTest
import com.site.support.LearningCouponIssueStrategies
import com.site.support.couponEventFixture
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * 100장짜리 이벤트에 300명이 동시에 발급을 요청한다. 동시성 제어 방식 네 가지를 같은 조건으로 비교한다.
 * 운영 코드의 즉시 발급 입구(CouponIssueFacade)는 두 방식(원자적 UPDATE, 분산락) 모두 확인한다.
 * 실행 결과(성공 수, 실패 사유, 소요 시간)는 로그의 "동시 발급 결과" 줄에서 볼 수 있다.
 */
@IntegrationTest
class CouponIssueConcurrencyTest @Autowired constructor(
    private val couponIssueService: CouponIssueService,
    private val learningStrategies: LearningCouponIssueStrategies,
    private val startedCouponEvents: StartedCouponEventCache,
    private val bulkhead: CouponIssueBulkhead,
    private val metrics: CouponIssueMetrics,
    private val couponEventRepository: CouponEventRepository,
    private val issuedCouponRepository: IssuedCouponRepository,
    private val redissonClient: RedissonClient,
    private val dataSource: DataSource,
    private val transactionTemplate: TransactionTemplate,
    private val meterRegistry: MeterRegistry,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @AfterEach
    fun tearDown() {
        issuedCouponRepository.deleteAllInBatch()
        couponEventRepository.deleteAllInBatch()
    }

    @Test
    fun `락이 없으면 동시 요청에서 발급 수량이 어긋난다`() {
        val result = issueConcurrently("락 없음", learningStrategies::issueWithoutLock)

        // 타이밍에 따라 결과가 달라지는 문제라 "정확히 몇 장"이 아니라 "정합성이 깨졌는지"를 확인한다.
        // 초과 발급(쿠폰 > 100장) 또는 Lost Update(수량 컬럼 ≠ 실제 발급 건수) 중 하나는 거의 확실하게 일어난다.
        assertTrue(
            result.issuedCouponCount > TOTAL_QUANTITY || result.issuedQuantity.toLong() != result.issuedCouponCount,
        ) { "정합성이 깨지지 않았습니다: $result" }
    }

    @Test
    fun `비관적 락 - 정확히 100장만 발급된다`() {
        assertExactlyTotalIssued(issueConcurrently("비관적 락", learningStrategies::issueWithPessimisticLock))
    }

    @Test
    fun `분산락 - 정확히 100장만 발급된다`() {
        assertExactlyTotalIssued(issueConcurrently("분산락", couponIssueService::issueWithDistributedLock))
    }

    @Test
    fun `원자적 UPDATE - 정확히 100장만 발급된다`() {
        assertExactlyTotalIssued(issueConcurrently("원자적 UPDATE", ::issueWithAtomicUpdate))
    }

    @ParameterizedTest
    @EnumSource(DirectIssueStrategy::class)
    fun `즉시 발급 입구(사전 검사 + 설정한 방식) - 정확히 100장만 발급된다`(strategy: DirectIssueStrategy) {
        assertExactlyTotalIssued(issueConcurrently("사전 검사 + $strategy", facade(strategy)::issue))
    }

    @Test
    fun `한 번 소진을 확인한 뒤로는 락을 기다리지 않고 바로 거절된다`() {
        val facade = facade(DirectIssueStrategy.DISTRIBUTED_LOCK)
        val couponEventId = couponEventRepository.save(couponEventFixture(totalQuantity = 1)).id
        facade.issue(couponEventId, memberId = 1L)
        // 이 서버가 소진을 처음 확인하는 요청은 락 안에서 거절된다
        assertEquals(ErrorCode.COUPON_SOLD_OUT, assertThrows<ServiceException> { facade.issue(couponEventId, memberId = 2L) }.errorCode)

        // 다른 누군가가 락을 오래 쥐고 있는 상황 (락 이름은 DistributedLockAspect 의 "lock:" + key)
        val lock = redissonClient.getLock("lock:coupon-event:$couponEventId")
        lock.lock()
        try {
            // Redisson 락은 같은 스레드면 재진입되므로 반드시 다른 스레드에서 요청한다
            val errorCode = CompletableFuture.supplyAsync {
                assertThrows<ServiceException> { facade.issue(couponEventId, memberId = 3L) }.errorCode
            }.get(10, TimeUnit.SECONDS)

            // 사전 검사가 없었다면 락을 5초(waitTime) 기다린 뒤 LOCK_ACQUISITION_FAILED 가 났을 것이다
            assertEquals(ErrorCode.COUPON_SOLD_OUT, errorCode)
        } finally {
            lock.unlock()
        }
    }

    @Test
    fun `발급이 몰려도 발급 단계는 커넥션을 최대 maxConcurrent 개만 쓰고, 같은 풀을 쓰는 다른 조회는 그동안 커넥션을 얻는다`() {
        val pool = dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean
        val couponEventId = couponEventRepository.save(couponEventFixture()).id
        val limit = CouponIssueProperties.Bulkhead(maxConcurrent = 2, maxWait = Duration.ofSeconds(30))
        val facade = CouponIssueFacade(
            startedCouponEvents,
            couponIssueService,
            CouponIssueBulkhead(CouponIssueProperties(bulkhead = limit), meterRegistry),
            CouponIssueProperties(directStrategy = DirectIssueStrategy.ATOMIC_UPDATE),
            metrics,
        )

        // 다른 트랜잭션이 이벤트 행을 잠근 상황: 발급의 UPDATE 는 커넥션을 쥔 채 행 잠금을 기다린다
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val locker = CompletableFuture.runAsync {
            transactionTemplate.executeWithoutResult {
                couponEventRepository.findByIdForUpdate(couponEventId)
                locked.countDown()
                release.await()
            }
        }
        locked.await(5, TimeUnit.SECONDS)
        val executor = Executors.newFixedThreadPool(10)
        val requests = (1L..10L).map { memberId -> CompletableFuture.supplyAsync({ facade.issue(couponEventId, memberId) }, executor) }

        // 행을 잠근 1개 + 행 잠금을 기다리는 발급 2개. 나머지 8개 요청은 커넥션 없이 자리를 기다린다
        await atMost Duration.ofSeconds(5) untilAsserted { assertEquals(3, pool.activeConnections) }
        await.during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilAsserted { assertEquals(3, pool.activeConnections) }
        assertTimeoutPreemptively(Duration.ofSeconds(2)) { couponEventRepository.count() }

        release.countDown()
        locker.get(5, TimeUnit.SECONDS)
        requests.forEach { it.get(10, TimeUnit.SECONDS) }
        executor.shutdown()
        assertEquals(10L, issuedCouponRepository.countByCouponEventId(couponEventId))
    }

    @Test
    fun `같은 회원이 동시에 여러 번 요청하면 unique 제약으로 1장만 발급되고, 실패한 요청의 수량 증가는 롤백된다`() {
        val result = issueConcurrently(
            label = "같은 회원 중복 요청",
            issue = ::issueWithAtomicUpdate,
            requestCount = 20,
            memberIdOf = { SAME_MEMBER_ID },
        )

        assertEquals(1, result.successCount)
        assertEquals(1L, result.issuedCouponCount)
        assertEquals(1, result.issuedQuantity)
        assertEquals(mapOf("ALREADY_ISSUED_COUPON" to 19), result.failReasons)
    }

    private fun issueWithAtomicUpdate(couponEventId: Long, memberId: Long): IssuedCoupon =
        couponIssueService.issueWithAtomicUpdate(couponEventId, memberId, CouponIssueMode.DIRECT)

    private fun facade(strategy: DirectIssueStrategy) =
        CouponIssueFacade(startedCouponEvents, couponIssueService, bulkhead, CouponIssueProperties(directStrategy = strategy), metrics)

    private fun assertExactlyTotalIssued(result: IssueResult) {
        assertEquals(TOTAL_QUANTITY, result.successCount)
        assertEquals(TOTAL_QUANTITY, result.issuedQuantity)
        assertEquals(TOTAL_QUANTITY.toLong(), result.issuedCouponCount)
        // 실패는 전부 "소진" 때문이어야 한다. (데드락·락 타임아웃 같은 예외가 섞이면 안 된다)
        assertEquals(mapOf("COUPON_SOLD_OUT" to REQUEST_COUNT - TOTAL_QUANTITY), result.failReasons)
    }

    private data class IssueResult(
        val label: String,
        val successCount: Int,
        val failReasons: Map<String, Int>,
        val issuedQuantity: Int,
        val issuedCouponCount: Long,
        val elapsedMillis: Long,
    )

    /**
     * 요청 requestCount 개를 스레드 THREAD_COUNT 개로 동시에 보낸다.
     * startSignal 로 모든 작업을 대기시켰다가 한 번에 출발시켜서, 요청이 최대한 겹치게 만든다.
     *
     * issue 파라미터에는 learningStrategies::issueWithoutLock 같은 "함수 참조"를 넘긴다.
     */
    private fun issueConcurrently(
        label: String,
        issue: (Long, Long) -> IssuedCoupon,
        requestCount: Int = REQUEST_COUNT,
        memberIdOf: (Int) -> Long = { index -> index + 1L },
    ): IssueResult {
        val couponEventId = couponEventRepository.save(couponEventFixture(totalQuantity = TOTAL_QUANTITY)).id

        val executor = Executors.newFixedThreadPool(THREAD_COUNT)
        val startSignal = CountDownLatch(1)
        val doneSignal = CountDownLatch(requestCount)
        val successCount = AtomicInteger()
        val failReasons = ConcurrentHashMap<String, AtomicInteger>()

        repeat(requestCount) { index ->
            executor.execute {
                try {
                    startSignal.await()
                    issue(couponEventId, memberIdOf(index))
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    val reason = (e as? ServiceException)?.errorCode?.name ?: e.javaClass.simpleName
                    failReasons.computeIfAbsent(reason) { AtomicInteger() }.incrementAndGet()
                } finally {
                    doneSignal.countDown()
                }
            }
        }

        val startedAt = System.nanoTime()
        startSignal.countDown()
        check(doneSignal.await(1, TimeUnit.MINUTES)) { "동시 요청이 1분 안에 끝나지 않았습니다" }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        executor.shutdown()

        return IssueResult(
            label = label,
            successCount = successCount.get(),
            failReasons = failReasons.mapValues { it.value.get() },
            issuedQuantity = requireNotNull(couponEventRepository.findByIdOrNull(couponEventId)).issuedQuantity,
            issuedCouponCount = issuedCouponRepository.countByCouponEventId(couponEventId),
            elapsedMillis = elapsedMillis,
        ).also { log.info("동시 발급 결과: {}", it) }
    }

    companion object {
        private const val TOTAL_QUANTITY = 100
        private const val REQUEST_COUNT = 300
        private const val THREAD_COUNT = 32
        private const val SAME_MEMBER_ID = 1L
    }
}
