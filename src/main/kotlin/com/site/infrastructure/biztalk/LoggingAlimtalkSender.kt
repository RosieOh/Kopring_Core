package com.site.infrastructure.biztalk

import com.site.domain.notification.port.AlimtalkMessage
import com.site.domain.notification.port.AlimtalkSendResult
import com.site.domain.notification.port.AlimtalkSender
import com.site.independent.util.maskPhoneNumber
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** 로컬/테스트용. 실제로 보내지 않고 로그만 남긴다. */
@Component
@ConditionalOnProperty(prefix = "biztalk", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class LoggingAlimtalkSender(
    private val properties: BiztalkProperties,
) : AlimtalkSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: AlimtalkMessage): AlimtalkSendResult {
        log.info(
            "[알림톡 로그 대체] to={}, template={}, variables={}",
            message.to.maskPhoneNumber(),
            properties.templateIdOf(message.template),
            message.variables,
        )
        return AlimtalkSendResult.Success
    }
}
