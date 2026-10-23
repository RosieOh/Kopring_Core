package com.site.infrastructure.biztalk

import com.site.domain.notification.port.AlimtalkMessage
import com.site.domain.notification.port.AlimtalkSendResult
import com.site.domain.notification.port.AlimtalkSender
import net.nurigo.sdk.NurigoApp
import net.nurigo.sdk.message.exception.NurigoApiKeyException
import net.nurigo.sdk.message.exception.NurigoBadRequestException
import net.nurigo.sdk.message.exception.NurigoInvalidApiKeyException
import net.nurigo.sdk.message.exception.NurigoMessageNotReceivedException
import net.nurigo.sdk.message.exception.NurigoUnregisteredSenderIdException
import net.nurigo.sdk.message.model.KakaoOption
import net.nurigo.sdk.message.model.Message
import net.nurigo.sdk.message.request.SingleMessageSendingRequest
import net.nurigo.sdk.message.service.DefaultMessageService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Solapi(nurigo SDK)로 카카오 알림톡을 실제 발송한다. biztalk.enabled=true 일 때만 빈으로 등록된다.
 */
@Component
@ConditionalOnProperty(prefix = "biztalk", name = ["enabled"], havingValue = "true")
class SolapiAlimtalkSender(
    private val properties: BiztalkProperties,
) : AlimtalkSender {

    // init 블록과 프로퍼티 초기화는 선언 순서대로 실행된다. 설정 검증을 클라이언트 생성보다 먼저 둔다.
    init {
        require(
            listOf(properties.apiKey, properties.apiSecret, properties.senderNumber, properties.pfId).all { it.isNotBlank() },
        ) { "biztalk.enabled=true 이면 biztalk.api-key, api-secret, sender-number, pf-id 가 모두 필요합니다." }
    }

    private val messageService: DefaultMessageService =
        NurigoApp.initialize(properties.apiKey, properties.apiSecret, SOLAPI_DOMAIN)

    /** runCatching: 예외를 Result 로 감싸서, 성공/실패를 각각 값으로 변환한다. */
    override fun send(message: AlimtalkMessage): AlimtalkSendResult =
        runCatching { messageService.sendOne(SingleMessageSendingRequest(message.toSolapiMessage())) }
            .fold(
                onSuccess = { AlimtalkSendResult.Success },
                onFailure = {
                    AlimtalkSendResult.Failure(reason = it.message ?: it.javaClass.simpleName, retryable = it.isRetryable())
                },
            )

    private fun AlimtalkMessage.toSolapiMessage(): Message =
        Message(
            from = properties.senderNumber,
            to = to,
            kakaoOptions = KakaoOption(
                pfId = properties.pfId,
                templateId = properties.templateIdOf(template),
                // Kotlin 은 읽기 전용 Map 과 MutableMap 을 타입으로 구분한다. SDK 가 MutableMap 을 요구해서 복사본을 넘긴다.
                variables = variables.toMutableMap(),
            ),
        )

    // 요청 자체가 잘못된 경우(키 오류, 미등록 발신번호, 잘못된 수신번호 등)는 다시 보내도 실패하므로 재시도하지 않는다
    private fun Throwable.isRetryable(): Boolean =
        when (this) {
            is NurigoBadRequestException,
            is NurigoApiKeyException,
            is NurigoInvalidApiKeyException,
            is NurigoUnregisteredSenderIdException,
            is NurigoMessageNotReceivedException,
            -> false

            else -> true
        }

    companion object {
        private const val SOLAPI_DOMAIN = "https://api.solapi.com"
    }
}
