package com.site.domain.notification

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * @property maxAttempts 최대 발송 시도 횟수 (첫 시도 포함)
 * @property backoff n 번째 실패 뒤에는 backoff × n 만큼 미뤘다가 다시 보낸다
 * @property batchSize 스케줄러가 한 번에 집어 가는 건수
 * @property lease 선점한 뒤 결과를 기록하기까지 기다려 주는 시간. 넘기면 보내던 쪽이 죽은 것으로 보고 다시 집어 간다
 */
@ConfigurationProperties(prefix = "notification.outbox")
data class NotificationOutboxProperties(
    val maxAttempts: Int = 5,
    val backoff: Duration = Duration.ofSeconds(10),
    val batchSize: Int = 100,
    val lease: Duration = Duration.ofSeconds(30),
)
