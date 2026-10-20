package com.site.domain.coupon.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 알림 발송 재시도 설정. n 번째 재시도 전에는 backoff × n 만큼 기다린다. (1초, 2초, ...)
 */
@ConfigurationProperties(prefix = "notification.retry")
data class NotificationRetryProperties(
    val maxAttempts: Int = 3,
    val backoff: Duration = Duration.ofSeconds(1),
)
