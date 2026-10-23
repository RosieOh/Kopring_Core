package com.site.domain.auth.port

import com.site.domain.member.entity.MemberRole

/**
 * 토큰 발급 포트. domain 은 "토큰이 필요하다"만 알고, JWT 인지 다른 형식인지는 어댑터(JwtTokenProvider)가 정한다.
 */
interface TokenIssuer {
    fun createAccessToken(memberId: Long, role: MemberRole): String

    fun createRefreshToken(): String
}
