package com.site.presentation.coupon.stream

import com.site.domain.coupon.queue.CouponQueueService
import com.site.domain.coupon.queue.CouponQueueStatus
import com.site.presentation.coupon.dto.toResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 대기열 상태를 SSE(Server-Sent Events)로 밀어 준다. 연결 하나로 결과가 나올 때까지 기다린다.
 *
 * 클라이언트가 0.5초마다 묻는 대신, 이 서버가 0.5초마다 Redis 에서 상태를 확인하고 "바뀌었을 때만" 보낸다.
 * HTTP 요청·응답 왕복과 인증 처리가 연결 하나로 줄어든다. 결과(DONE)를 보내면 연결을 닫는다.
 *
 * 연결 목록은 서버(인스턴스)마다 따로 갖는다. 상태의 원본은 Redis 라서 서버가 여러 대여도 각자 자기 연결만 챙기면 된다.
 * 연결은 스레드를 붙잡지 않는다 (Servlet 비동기 처리).
 */
@Component
class CouponQueueStatusStreams(
    private val couponQueueService: CouponQueueService,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val subscriptions = ConcurrentHashMap<SseEmitter, Subscription>()

    private class Subscription(
        val couponEventId: Long,
        val memberId: Long,
    ) {
        @Volatile
        var lastSent: CouponQueueStatus? = null
    }

    fun subscribe(couponEventId: Long, memberId: Long): SseEmitter {
        val emitter = SseEmitter(TIMEOUT.toMillis())
        val subscription = Subscription(couponEventId, memberId)
        subscriptions[emitter] = subscription

        emitter.onCompletion { subscriptions.remove(emitter) }
        emitter.onTimeout {
            // 클라이언트가 다시 연결한다
            subscriptions.remove(emitter)
            emitter.complete()
        }
        emitter.onError { subscriptions.remove(emitter) }

        push(emitter, subscription) // 연결하자마자 현재 상태를 보낸다
        return emitter
    }

    /** 연결된 모든 사용자의 상태를 확인해, 바뀐 사람에게만 보낸다. (스케줄러) */
    fun pushAll() {
        subscriptions.forEach { (emitter, subscription) -> push(emitter, subscription) }
    }

    fun connectionCount(): Int = subscriptions.size

    private fun push(emitter: SseEmitter, subscription: Subscription) {
        val status = couponQueueService.getStatus(subscription.couponEventId, subscription.memberId)
        if (status == subscription.lastSent) return
        subscription.lastSent = status

        try {
            emitter.send(SseEmitter.event().name(EVENT_NAME).data(status.toResponse()))
            // 결과가 나왔거나 대기열에 없으면 더 보낼 것이 없다.
            // 목록에서 직접 뺀다. 연결하자마자 닫는 경우 onCompletion 콜백이 언제 불리는지는 서블릿 컨테이너마다 다르다.
            if (status is CouponQueueStatus.Done || status == CouponQueueStatus.NotInQueue) {
                subscriptions.remove(emitter)
                emitter.complete()
            }
        } catch (e: Exception) {
            // 클라이언트가 이미 끊은 경우
            log.debug("대기열 상태 전송 실패, 연결을 정리합니다. {}", e.message)
            subscriptions.remove(emitter)
        }
    }

    companion object {
        const val EVENT_NAME = "status"
        private val TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
