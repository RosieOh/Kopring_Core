package com.site.domain.coupon.service

import com.site.domain.coupon.event.CouponIssuedEvent
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.member.repository.MemberRepository
import com.site.domain.notification.NotificationResult
import com.site.domain.notification.port.AlimtalkMessage
import com.site.domain.notification.port.AlimtalkSendResult
import com.site.domain.notification.port.AlimtalkSender
import com.site.domain.notification.port.AlimtalkTemplate
import com.site.independent.util.maskPhoneNumber
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * 쿠폰 발급 알림 한 건을 한 번 보내고 결과를 돌려준다.
 * 언제 다시 보낼지(재시도)는 이 클래스가 아니라 Outbox(NotificationOutboxProcessor)가 정한다.
 */
@Service
class CouponNotificationService(
    private val memberRepository: MemberRepository,
    private val couponEventRepository: CouponEventRepository,
    private val alimtalkSender: AlimtalkSender,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun notifyIssued(event: CouponIssuedEvent): NotificationResult {
        val member = memberRepository.findByIdOrNull(event.memberId)
            ?: return NotificationResult.Skipped("회원이 없습니다")
        val phoneNumber = member.phoneNumber
            ?: return NotificationResult.Skipped("휴대폰 번호가 없습니다")
        val couponEvent = couponEventRepository.findByIdOrNull(event.couponEventId)
            ?: return NotificationResult.Skipped("쿠폰 이벤트가 없습니다")

        val message = AlimtalkMessage(
            to = phoneNumber,
            template = AlimtalkTemplate.COUPON_ISSUED,
            variables = mapOf(
                "#{닉네임}" to member.nickname,
                "#{이벤트명}" to couponEvent.name,
                "#{할인}" to couponEvent.discount.toDisplayText(),
            ),
        )

        // sealed 타입이라 두 경우를 빠뜨리면 컴파일 에러가 난다
        return when (val result = alimtalkSender.send(message)) {
            AlimtalkSendResult.Success -> {
                log.info("쿠폰 발급 알림톡 발송 완료. to={}, {}", phoneNumber.maskPhoneNumber(), event)
                NotificationResult.Sent
            }
            is AlimtalkSendResult.Failure -> {
                log.warn("쿠폰 발급 알림톡 발송 실패. retryable={}, reason={}, {}", result.retryable, result.reason, event)
                NotificationResult.Failed(reason = result.reason, retryable = result.retryable)
            }
        }
    }
}
