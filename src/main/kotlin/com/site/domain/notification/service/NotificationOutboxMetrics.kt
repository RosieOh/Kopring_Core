package com.site.domain.notification.service

import com.site.domain.notification.entity.NotificationType
import com.site.domain.notification.entity.OutboxStatus
import com.site.domain.notification.repository.NotificationOutboxRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * - notification_outbox_results_total{type, result="sent|skipped|retry|failed"}: 처리 결과
 * - notification_outbox_pending: 아직 못 보낸 알림 수. 계속 늘어나면 발송이 밀리거나 막힌 것이다.
 */
@Component
class NotificationOutboxMetrics(
    private val meterRegistry: MeterRegistry,
    outboxRepository: NotificationOutboxRepository,
) {

    init {
        // 게이지 값은 Prometheus 가 가져갈 때(scrape)마다 계산된다. (status, next_attempt_at) 인덱스를 타는 count 쿼리다.
        Gauge.builder("notification.outbox.pending", outboxRepository) { it.countByStatus(OutboxStatus.PENDING).toDouble() }
            .description("아직 보내지 못한 알림 수")
            .strongReference(true)
            .register(meterRegistry)
    }

    fun record(type: NotificationType, result: String) {
        meterRegistry.counter("notification.outbox.results", "type", type.name.lowercase(), "result", result).increment()
    }
}
