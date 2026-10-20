package com.site.infrastructure.security.handler

import com.fasterxml.jackson.databind.ObjectMapper
import com.site.independent.type.ErrorCode
import com.site.infrastructure.security.jwt.JwtAuthenticationFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

/** 인증이 필요한 경로에 인증 없이 접근했을 때 (401) */
@Component
class JwtAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        // 필터가 남긴 토큰 에러가 있으면 그걸, 없으면 "토큰 자체가 없음"으로 응답
        val errorCode = request.getAttribute(JwtAuthenticationFilter.JWT_ERROR_ATTRIBUTE) as? ErrorCode
            ?: ErrorCode.EMPTY_AUTHENTICATION

        response.writeErrorResponse(objectMapper, errorCode)
    }
}
