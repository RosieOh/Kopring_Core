package com.site.infrastructure.security.jwt

import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import com.site.infrastructure.redis.AccessTokenBlacklist
import com.site.infrastructure.security.AuthMember
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 토큰이 유효하면 SecurityContext 에 인증 정보를 넣고, 아니면 에러 코드만 request 에 남긴 채 통과시킨다.
 * 실제 401 응답은 인증이 필요한 경로에서 JwtAuthenticationEntryPoint 가 만든다.
 *
 * @Component 로 등록하지 않는다. 빈으로 등록하면 Spring Boot 가 서블릿 필터로도 자동 등록해서
 * Security 필터 체인 밖에서 한 번 더 실행된다.
 */
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val accessTokenBlacklist: AccessTokenBlacklist,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        resolveToken(request)?.let { token ->
            try {
                val authMember = authenticate(token)
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(authMember, null, authMember.authorities)
            } catch (e: ServiceException) {
                request.setAttribute(JWT_ERROR_ATTRIBUTE, e.errorCode)
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun authenticate(token: String): AuthMember {
        val authMember = jwtTokenProvider.parseAccessToken(token)
        if (accessTokenBlacklist.contains(authMember.tokenId)) {
            throw ServiceException(ErrorCode.LOGGED_OUT_TOKEN)
        }
        return authMember
    }

    private fun resolveToken(request: HttpServletRequest): String? =
        request.getHeader(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.takeIf { it.isNotBlank() }

    companion object {
        const val JWT_ERROR_ATTRIBUTE = "jwtErrorCode"
        private const val BEARER_PREFIX = "Bearer "
    }
}
