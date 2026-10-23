package com.site.support

import com.site.domain.notification.port.AlimtalkMessage
import com.site.domain.notification.port.AlimtalkSendResult
import com.site.domain.notification.port.AlimtalkSender
import org.springframework.boot.test.context.TestComponent
import org.springframework.context.annotation.Primary
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 통합 테스트용 가짜 발송기. 실제로 보내지 않고 받은 메시지를 기록한다.
 * @Primary 라서 LoggingAlimtalkSender 대신 주입된다.
 * 비동기 스레드에서 호출되므로 스레드 안전한 컬렉션을 쓴다.
 */
@TestComponent
@Primary
class FakeAlimtalkSender : AlimtalkSender {

    val sentMessages = CopyOnWriteArrayList<AlimtalkMessage>()

    /** 외부 API 가 느린 상황을 흉내 낸다 */
    @Volatile
    var delay: Duration = Duration.ZERO

    override fun send(message: AlimtalkMessage): AlimtalkSendResult {
        Thread.sleep(delay.toMillis())
        sentMessages += message
        return AlimtalkSendResult.Success
    }

    fun reset() {
        sentMessages.clear()
        delay = Duration.ZERO
    }
}
