package com.site.domain.notification.entity

import com.site.domain.common.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Duration
import java.time.LocalDateTime

/**
 * 보내야 할 알림 한 건. 비즈니스 트랜잭션(발급)과 같은 트랜잭션에서 저장된다. (Transactional Outbox)
 *
 * 재시도는 스레드를 재우는 대신 다음 시도 시각(nextAttemptAt)을 미뤄 두고, 스케줄러가 그때 다시 집어 간다.
 */
@Entity
@Table(name = "notification_outbox")
class NotificationOutbox(
    type: NotificationType,
    payload: String,
    nextAttemptAt: LocalDateTime,
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "event_type", nullable = false, length = 50)
    val type: NotificationType = type

    /** 알림에 필요한 정보(JSON). 메시지 문구는 보낼 때 최신 데이터로 만든다. */
    @Column(nullable = false, length = 1000)
    val payload: String = payload

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    var status: OutboxStatus = OutboxStatus.PENDING
        protected set

    @Column(nullable = false)
    var attemptCount: Int = 0
        protected set

    @Column(nullable = false)
    var nextAttemptAt: LocalDateTime = nextAttemptAt
        protected set

    @Column(length = 500)
    var lastError: String? = null
        protected set

    var processedAt: LocalDateTime? = null
        protected set

    fun markSent(now: LocalDateTime) {
        attemptCount++
        finish(OutboxStatus.SENT, now, error = null)
    }

    fun markSkipped(now: LocalDateTime, reason: String) {
        attemptCount++
        finish(OutboxStatus.SKIPPED, now, error = reason)
    }

    /**
     * 실패를 기록한다. 다시 보낼 만한 실패이고 횟수가 남았으면 backoff × 시도 횟수 뒤로 미루고, 아니면 FAILED 로 끝낸다.
     */
    fun recordFailure(
        now: LocalDateTime,
        reason: String,
        retryable: Boolean,
        maxAttempts: Int,
        backoff: Duration,
    ) {
        attemptCount++
        if (retryable && attemptCount < maxAttempts) {
            lastError = reason.take(MAX_ERROR_LENGTH)
            nextAttemptAt = now.plus(backoff.multipliedBy(attemptCount.toLong()))
        } else {
            finish(OutboxStatus.FAILED, now, error = reason)
        }
    }

    private fun finish(
        status: OutboxStatus,
        now: LocalDateTime,
        error: String?,
    ) {
        this.status = status
        this.processedAt = now
        this.lastError = error?.take(MAX_ERROR_LENGTH)
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 500
    }
}
