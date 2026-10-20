package com.site.infrastructure.security

import com.site.domain.member.entity.MemberRole
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.time.Instant

/**
 * SecurityContext 에 담기는 인증 주체.
 * 컨트롤러에서 @AuthenticationPrincipal authMember: AuthMember 로 꺼내 쓴다.
 *
 * tokenId / tokenExpiresAt 은 "어떤 access token 으로 인증됐는지"를 나타낸다. (로그아웃 시 블랙리스트 등록용)
 */
data class AuthMember(
    val memberId: Long,
    val role: MemberRole,
    val tokenId: String,
    val tokenExpiresAt: Instant,
) {
    val authorities: List<GrantedAuthority>
        get() = listOf(SimpleGrantedAuthority(role.authority))
}
