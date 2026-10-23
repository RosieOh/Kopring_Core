package com.site.domain.notification.service

import com.site.independent.config.AsyncExecutors
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 빠른 경로: 발급이 커밋되면 별도 스레드에서 바로 보내 본다.
 * 이 경로가 실패하거나 사라져도(서버 재시작, 스레드 풀 포화) Outbox 에 기록이 남아 있으므로 스케줄러가 나중에 보낸다.
 */
@Component
class NotificationOutboxEventListener(
    private val processor: NotificationOutboxProcessor,
) {

    @Async(AsyncExecutors.NOTIFICATION)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handle(event: NotificationOutboxRecorded) {
        processor.process(event.outboxId)
    }
}
