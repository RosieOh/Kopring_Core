package com.site.domain.notification.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.site.domain.coupon.service.CouponNotificationService
import com.site.domain.notification.NotificationOutboxProperties
import com.site.domain.notification.NotificationResult
import com.site.domain.notification.entity.NotificationOutbox
import com.site.domain.notification.entity.NotificationType
import com.site.domain.notification.entity.OutboxStatus
import com.site.domain.notification.repository.NotificationOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime

/**
 * Outbox 한 건을 보낸다: 선점 → (트랜잭션 밖에서) 발송 → 결과 기록.
 *
 * 외부 API 호출을 트랜잭션 밖에 두는 이유: 알림톡 응답을 기다리는 동안 DB 커넥션과 행 잠금을 붙잡지 않기 위해서다.
 * 그래서 @Transactional 대신 TransactionTemplate 으로 짧은 트랜잭션을 두 번 연다.
 */
@Component
class NotificationOutboxProcessor(
    private val outboxRepository: NotificationOutboxRepository,
    private val couponNotificationService: CouponNotificationService,
    private val objectMapper: ObjectMapper,
    private val properties: NotificationOutboxProperties,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 보낼 차례가 된 건을 모두 처리한다. (스케줄러) */
    fun processDue() {
        outboxRepository.findDueIds(OutboxStatus.PENDING, now(), PageRequest.of(0, properties.batchSize))
            .forEach { outboxId ->
                runCatching { process(outboxId) }
                    .onFailure { log.error("Outbox 처리 중 오류. outboxId={}", outboxId, it) }
            }
    }

    fun process(outboxId: Long) {
        val now = now()
        val claimed = transactionTemplate.execute {
            outboxRepository.claim(outboxId, OutboxStatus.PENDING, now, now.plus(properties.lease))
        } == 1
        if (!claimed) return // 다른 쪽이 이미 보냈거나 보내는 중

        val outbox = outboxRepository.findByIdOrNull(outboxId) ?: return
        val result = runCatching { send(outbox) }
            .getOrElse { NotificationResult.Failed(reason = it.message ?: it.javaClass.simpleName, retryable = true) }

        transactionTemplate.executeWithoutResult {
            val current = outboxRepository.findByIdOrNull(outboxId) ?: return@executeWithoutResult
            current.apply(result, now())
        }
    }

    private fun send(outbox: NotificationOutbox): NotificationResult =
        when (outbox.type) {
            NotificationType.COUPON_ISSUED -> couponNotificationService.notifyIssued(objectMapper.readValue(outbox.payload))
        }

    private fun NotificationOutbox.apply(result: NotificationResult, now: LocalDateTime) {
        when (result) {
            NotificationResult.Sent -> markSent(now)
            is NotificationResult.Skipped -> markSkipped(now, result.reason)
            is NotificationResult.Failed -> {
                recordFailure(now, result.reason, result.retryable, properties.maxAttempts, properties.backoff)
                if (status == OutboxStatus.FAILED) {
                    // 운영이라면 여기서 알람을 보낸다. FAILED 로 남은 건이 재발송·집계의 근거가 된다.
                    log.error("알림 최종 실패. outboxId={}, attempts={}, reason={}", id, attemptCount, result.reason)
                }
            }
        }
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)
}
