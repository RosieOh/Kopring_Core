package com.site.domain.notification.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.site.domain.coupon.event.CouponIssuedEvent
import com.site.domain.notification.entity.NotificationOutbox
import com.site.domain.notification.entity.NotificationType
import com.site.domain.notification.repository.NotificationOutboxRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class NotificationOutboxService(
    private val outboxRepository: NotificationOutboxRepository,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {

    /**
     * 발급 알림을 Outbox 에 기록한다.
     *
     * MANDATORY: 호출하는 쪽에 트랜잭션이 없으면 예외가 난다. Outbox 는 "비즈니스 변경과 같은 트랜잭션"이어야 의미가 있기 때문이다.
     * (따로 커밋되면 발급은 됐는데 알림 기록은 없거나, 그 반대가 생긴다)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun recordCouponIssued(event: CouponIssuedEvent) {
        val outbox = outboxRepository.save(
            NotificationOutbox(
                type = NotificationType.COUPON_ISSUED,
                payload = objectMapper.writeValueAsString(event),
                nextAttemptAt = LocalDateTime.now(clock),
            ),
        )
        // 커밋되면 바로 보내 보라는 신호 (빠른 경로). 이 신호가 사라져도 스케줄러가 나중에 보낸다.
        eventPublisher.publishEvent(NotificationOutboxRecorded(outbox.id))
    }
}

data class NotificationOutboxRecorded(
    val outboxId: Long,
)
