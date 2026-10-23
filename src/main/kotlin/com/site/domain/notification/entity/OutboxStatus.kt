package com.site.domain.notification.entity

enum class OutboxStatus {
    /** 보낼 차례를 기다리는 중 (재시도 대기 포함) */
    PENDING,

    SENT,

    /** 보낼 대상이 아니다 (휴대폰 번호 없음 등) */
    SKIPPED,

    /** 최대 횟수까지 실패했거나, 다시 보내도 소용없는 실패 */
    FAILED,
}
