package com.site.infrastructure.biztalk

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * biztalk.enabled=true 일 때만 실제로 발송한다. 기본값(false)이면 LoggingAlimtalkSender 가 로그로 대신한다.
 * 키 값은 Solapi 콘솔에서 발급받아 환경변수로 주입한다. (코드/설정 파일에 커밋하지 않는다)
 */
@ConfigurationProperties(prefix = "biztalk")
data class BiztalkProperties(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val apiSecret: String = "",
    val senderNumber: String = "",
    val pfId: String = "",
    val couponIssuedTemplateId: String = "COUPON_ISSUED",
)
