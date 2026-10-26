package com.site.domain.coupon.service

import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 즉시 발급 단계의 동시 실행 수를 제한한다. (bulkhead — 배의 격벽처럼, 한 칸에 물이 차도 다른 칸은 지킨다)
 *
 * 원자적 UPDATE 는 모든 발급이 이벤트 한 행의 잠금에 줄을 서고, 줄을 서는 동안 DB 커넥션을 쥔다.
 * 행 잠금 때문에 발급은 어차피 하나씩 진행되므로 커넥션을 많이 쥐어도 빨라지지 않고, 같은 풀을 쓰는 다른 API 만 커넥션을 못 얻는다.
 * 그래서 발급은 커넥션을 최대 maxConcurrent 개까지만 쓰게 하고, 나머지 요청은 커넥션 없이 여기서 기다리게 한다.
 * 커넥션 풀을 둘로 나누는 것과 같은 효과를, 데이터소스를 하나 더 만들지 않고 얻는다.
 *
 * - 공정(fair) 모드: 먼저 기다린 요청이 먼저 들어간다. 비공정 모드는 막 도착한 요청이 비는 자리를 가로챌 수 있다. (선착순의 새치기)
 * - 서버마다 따로 센다. 서버가 N 대면 DB 전체로는 N × maxConcurrent 개까지 쓴다.
 */
@Component
class CouponIssueBulkhead(
    properties: CouponIssueProperties,
    private val meterRegistry: MeterRegistry,
) {

    init {
        require(properties.bulkhead.maxConcurrent > 0) { "maxConcurrent must be positive: ${properties.bulkhead.maxConcurrent}" }
    }

    private val permits = Semaphore(properties.bulkhead.maxConcurrent, true)
    private val maxWaitMillis = properties.bulkhead.maxWait.toMillis()

    fun <T> execute(action: () -> T): T {
        val waitStartedAt = System.nanoTime()
        val acquired = permits.tryAcquire(maxWaitMillis, TimeUnit.MILLISECONDS)
        recordWait(acquired, System.nanoTime() - waitStartedAt)
        if (!acquired) {
            throw ServiceException(ErrorCode.COUPON_ISSUE_BUSY)
        }

        try {
            return action()
        } finally {
            permits.release()
        }
    }

    /** 자리를 얻기까지 기다린 시간. Prometheus 에서는 coupon_issue_bulkhead_wait_seconds_bucket{result} 로 보인다. */
    private fun recordWait(acquired: Boolean, nanos: Long) {
        Timer.builder("coupon.issue.bulkhead.wait")
            .description("즉시 발급 동시 실행 자리를 얻기까지 기다린 시간")
            .tag("result", if (acquired) "acquired" else "timeout")
            .publishPercentileHistogram()
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS)
    }
}
