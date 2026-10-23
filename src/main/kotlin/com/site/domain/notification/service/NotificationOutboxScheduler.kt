package com.site.domain.notification.service

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 느린 경로: 보낼 차례가 된 PENDING 건(빠른 경로 유실분, 재시도 대기분)을 주기적으로 보낸다. */
@Component
@ConditionalOnProperty(prefix = "notification.outbox.scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class NotificationOutboxScheduler(
    private val processor: NotificationOutboxProcessor,
) {

    @Scheduled(fixedDelayString = "\${notification.outbox.scheduler.interval-millis:5000}")
    fun run() {
        processor.processDue()
    }
}
