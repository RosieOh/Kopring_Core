package com.site.infrastructure.redis.lock

import com.site.independent.error.exception.ServiceException
import com.site.independent.lock.DistributedLock
import com.site.independent.parser.CustomSpringELParser
import com.site.independent.type.ErrorCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.redisson.api.RedissonClient
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * @DistributedLock 이 붙은 메서드를 Redisson 락으로 감싼다.
 *
 * 순서: 트랜잭션 인터셉터(기본 LOWEST_PRECEDENCE)보다 한 칸 바깥에서 실행되게 한다.
 * 트랜잭션 프록시가 락보다 바깥에 있으면 "트랜잭션 시작 → 락 → 해제 → 커밋" 순서가 되어 락이 의미를 잃는다.
 *
 * HIGHEST_PRECEDENCE 를 쓰면 안 된다. 스프링 내부의 ExposeInvocationInterceptor 보다 앞서 실행돼서
 * @annotation(distributedLock) 같은 파라미터 바인딩이 실패한다. ("JoinPointMatch was NOT bound in invocation")
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
class DistributedLockAspect(
    private val redissonClient: RedissonClient,
    private val newTransactionRunner: NewTransactionRunner,
    private val meterRegistry: MeterRegistry,
) {

    @Around("@annotation(distributedLock)")
    fun lock(joinPoint: ProceedingJoinPoint, distributedLock: DistributedLock): Any? {
        val lockName = LOCK_PREFIX + resolveKey(joinPoint, distributedLock.key)
        val lock = redissonClient.getLock(lockName)

        val waitStartedAt = System.nanoTime()
        val acquired = try {
            lock.tryLock(distributedLock.waitTime, distributedLock.leaseTime, distributedLock.timeUnit)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ServiceException(ErrorCode.LOCK_ACQUISITION_FAILED)
        }
        recordWait(distributedLock.key, acquired, System.nanoTime() - waitStartedAt)
        if (!acquired) {
            throw ServiceException(ErrorCode.LOCK_ACQUISITION_FAILED)
        }

        try {
            return newTransactionRunner.proceed(joinPoint)
        } finally {
            // 락을 쥔 스레드만 풀 수 있다. (다른 스레드가 풀려고 하면 Redisson 이 예외를 던진다)
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        }
    }

    /**
     * 락을 얻기까지 기다린 시간. Prometheus 에서는 lock_wait_seconds_bucket{lock, result} 로 보인다.
     * 태그에는 락 이름 전체(이벤트 ID 포함)가 아니라 종류만 넣는다. ('coupon-event:' + #id → coupon-event)
     */
    private fun recordWait(
        key: String,
        acquired: Boolean,
        nanos: Long,
    ) {
        Timer.builder("lock.wait")
            .description("분산락을 얻기까지 기다린 시간")
            .tag("lock", key.substringAfter("'").substringBefore(":"))
            .tag("result", if (acquired) "acquired" else "timeout")
            .publishPercentileHistogram()
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS)
    }

    // "'coupon-event:' + #couponEventId" 같은 SpEL 을 실제 파라미터 값으로 평가한다
    private fun resolveKey(joinPoint: ProceedingJoinPoint, key: String): String {
        val signature = joinPoint.signature as MethodSignature
        return CustomSpringELParser.getDynamicValue(signature.parameterNames, joinPoint.args, key).toString()
    }

    companion object {
        private const val LOCK_PREFIX = "lock:"
    }
}
