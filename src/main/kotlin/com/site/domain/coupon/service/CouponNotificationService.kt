package com.site.domain.coupon.service

import com.site.domain.coupon.event.CouponIssuedEvent
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.member.repository.MemberRepository
import com.site.infrastructure.biztalk.AlimtalkMessage
import com.site.infrastructure.biztalk.AlimtalkSendResult
import com.site.infrastructure.biztalk.AlimtalkSender
import com.site.infrastructure.biztalk.BiztalkProperties
import com.site.infrastructure.biztalk.maskPhoneNumber
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

@Service
class CouponNotificationService(
    private val memberRepository: MemberRepository,
    private val couponEventRepository: CouponEventRepository,
    private val alimtalkSender: AlimtalkSender,
    private val biztalkProperties: BiztalkProperties,
    private val retryProperties: NotificationRetryProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notifyIssued(event: CouponIssuedEvent) {
        val member = memberRepository.findByIdOrNull(event.memberId)
            ?: return log.warn("알림 대상 회원이 없습니다. {}", event)
        val phoneNumber = member.phoneNumber
            ?: return log.info("휴대폰 번호가 없어 알림을 보내지 않습니다. memberId={}", member.id)
        val couponEvent = couponEventRepository.findByIdOrNull(event.couponEventId)
            ?: return log.warn("알림 대상 쿠폰 이벤트가 없습니다. {}", event)

        val message = AlimtalkMessage(
            to = phoneNumber,
            templateId = biztalkProperties.couponIssuedTemplateId,
            variables = mapOf(
                "#{닉네임}" to member.nickname,
                "#{이벤트명}" to couponEvent.name,
                "#{할인}" to couponEvent.discount.toDisplayText(),
            ),
        )

        // sealed 타입이라 두 경우를 빠뜨리면 컴파일 에러가 난다
        when (val result = sendWithRetry(message)) {
            is AlimtalkSendResult.Success ->
                log.info("쿠폰 발급 알림톡 발송 완료. to={}, {}", phoneNumber.maskPhoneNumber(), event)

            is AlimtalkSendResult.Failure ->
                // 운영이라면 여기서 실패 내역을 테이블에 남기고 알람을 보낸다 (재발송 배치의 입력이 된다)
                log.error("쿠폰 발급 알림톡 최종 실패. reason={}, {}", result.reason, event)
        }
    }

    /**
     * 재시도 가능한 실패일 때만, 최대 maxAttempts 번까지 보낸다.
     * while 조건에서 result is Failure 를 확인하면, 같은 조건식 안의 result.retryable 은 스마트 캐스트로 바로 쓸 수 있다.
     */
    private fun sendWithRetry(message: AlimtalkMessage): AlimtalkSendResult {
        var result = alimtalkSender.send(message)
        var attempt = 1

        while (result is AlimtalkSendResult.Failure && result.retryable && attempt < retryProperties.maxAttempts) {
            log.warn("알림톡 발송 실패, 재시도합니다. attempt={}, reason={}", attempt, result.reason)
            Thread.sleep(retryProperties.backoff.multipliedBy(attempt.toLong()).toMillis())
            result = alimtalkSender.send(message)
            attempt++
        }
        return result
    }
}
