package com.site.domain.notification

/** 알림 한 건을 처리한 결과. Outbox 는 이 결과로 다음 상태를 정한다. */
sealed interface NotificationResult {
    data object Sent : NotificationResult

    /** 보낼 대상이 아니다 (휴대폰 번호 없음, 회원 탈퇴 등) — 다시 시도하지 않는다 */
    data class Skipped(
        val reason: String,
    ) : NotificationResult

    data class Failed(
        val reason: String,
        val retryable: Boolean,
    ) : NotificationResult
}
