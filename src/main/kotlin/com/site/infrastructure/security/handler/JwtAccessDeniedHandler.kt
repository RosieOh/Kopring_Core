package com.site.infrastructure.security.handler

import com.fasterxml.jackson.databind.ObjectMapper
import com.site.independent.type.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

/** 인증은 됐지만 권한이 부족할 때 (403) */
@Component
class JwtAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        response.writeErrorResponse(objectMapper, ErrorCode.ROLE_FORBIDDEN)
    }
}
