package com.site.domain.notification.entity

/** Outbox 에 쌓이는 알림의 종류. payload 를 어떤 타입으로 읽을지 정한다. */
enum class NotificationType {
    COUPON_ISSUED,
}
