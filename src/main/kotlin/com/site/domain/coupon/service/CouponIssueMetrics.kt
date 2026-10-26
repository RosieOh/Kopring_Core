package com.site.domain.coupon.service

import com.site.domain.coupon.queue.CouponQueueResult
import com.site.independent.type.ErrorCode
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 발급 요청 결과를 센다. Prometheus 에서는 coupon_issue_requests_total{mode, result} 로 보인다.
 *
 * Micrometer 는 지표용 파사드(로깅의 SLF4J 같은 것)라서 domain 에서 직접 써도 특정 모니터링 시스템에 묶이지 않는다.
 * result 태그 값은 enum 이름이라 종류가 정해져 있다. (태그 값 종류가 무한히 늘면 Prometheus 메모리가 터진다)
 */
@Component
class CouponIssueMetrics(
    private val meterRegistry: MeterRegistry,
) {

    fun recordDirectIssued() = record(mode = "direct", result = "issued")

    fun recordDirectFailed(errorCode: ErrorCode) = record(mode = "direct", result = resultOf(errorCode))

    fun recordQueue(result: CouponQueueResult, count: Long = 1) = record(mode = "queue", result = result.name.lowercase(), count = count)

    private fun record(
        mode: String,
        result: String,
        count: Long = 1,
    ) {
        meterRegistry.counter(NAME, "mode", mode, "result", result).increment(count.toDouble())
    }

    // 대기열 결과(CouponQueueResult)와 같은 이름을 써서, 두 방식의 결과를 대시보드에서 나란히 비교할 수 있게 한다
    private fun resultOf(errorCode: ErrorCode): String =
        when (errorCode) {
            ErrorCode.COUPON_SOLD_OUT -> "sold_out"
            ErrorCode.COUPON_EVENT_ENDED -> "ended"
            ErrorCode.COUPON_EVENT_NOT_STARTED -> "not_started"
            ErrorCode.ALREADY_ISSUED_COUPON -> "already_issued"
            ErrorCode.QUEUE_ONLY_COUPON_EVENT -> "mode_mismatch"
            ErrorCode.LOCK_ACQUISITION_FAILED -> "lock_timeout"
            ErrorCode.COUPON_EVENT_NOT_FOUND -> "not_found"
            else -> "failed"
        }

    companion object {
        const val NAME = "coupon.issue.requests"
    }
}
