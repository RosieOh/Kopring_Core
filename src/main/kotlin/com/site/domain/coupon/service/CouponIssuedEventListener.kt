package com.site.domain.coupon.service

import com.site.domain.coupon.event.CouponIssuedEvent
import com.site.independent.config.AsyncExecutors
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class CouponIssuedEventListener(
    private val couponNotificationService: CouponNotificationService,
) {

    /**
     * AFTER_COMMIT: 발급 트랜잭션이 커밋된 뒤에만 실행된다. 롤백되면(중복 발급 등) 알림도 나가지 않는다.
     * @Async: 별도 스레드에서 실행되므로 발급 API 응답이 외부 API 호출을 기다리지 않는다.
     *
     * 한계: 메모리 안에서만 전달되므로, 커밋 직후 서버가 죽으면 그 알림은 사라진다.
     * 반드시 보내야 하는 메시지라면 발급과 같은 트랜잭션에서 "보낼 메시지"를 테이블에 저장하고
     * 별도 워커가 보내는 Transactional Outbox 패턴을 쓴다.
     */
    @Async(AsyncExecutors.NOTIFICATION)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handle(event: CouponIssuedEvent) {
        couponNotificationService.notifyIssued(event)
    }
}
