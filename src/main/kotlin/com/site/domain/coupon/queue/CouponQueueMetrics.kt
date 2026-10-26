package com.site.domain.coupon.queue

import com.site.domain.coupon.queue.port.CouponQueueStore
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component

/**
 * 이벤트별 대기열 크기. Prometheus 에서는 coupon_queue_size{event_id, state="waiting|processing"} 로 보인다.
 *
 * 진행 중인 이벤트는 그때그때 달라서 게이지를 미리 등록해 둘 수 없다. MultiGauge 에 "지금 있는 행"을 통째로 덮어쓰면
 * 새 이벤트는 추가되고, 끝난 이벤트는 지표에서 사라진다.
 */
@Component
class CouponQueueMetrics(
    meterRegistry: MeterRegistry,
    private val couponQueueStore: CouponQueueStore,
) {

    private val queueSize = MultiGauge.builder(NAME)
        .description("선착순 대기열 인원")
        .register(meterRegistry)

    fun refresh(activeEventIds: Set<Long>) {
        val rows = activeEventIds.flatMap { couponEventId ->
            listOf(
                row(couponEventId, "waiting", couponQueueStore.waitingCount(couponEventId)),
                row(couponEventId, "processing", couponQueueStore.processingCount(couponEventId)),
            )
        }
        queueSize.register(rows, true)
    }

    private fun row(
        couponEventId: Long,
        state: String,
        count: Long,
    ): MultiGauge.Row<*> = MultiGauge.Row.of(Tags.of("event_id", couponEventId.toString(), "state", state), count)

    companion object {
        const val NAME = "coupon.queue.size"
    }
}
