package com.site.domain.auth.service

import com.site.domain.member.entity.Member
import com.site.domain.member.repository.MemberRepository
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import com.site.infrastructure.redis.AccessTokenBlacklist
import com.site.infrastructure.redis.RefreshTokenRepository
import com.site.infrastructure.security.AuthMember
import com.site.infrastructure.security.jwt.JwtTokenProvider
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AuthService(
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val accessTokenBlacklist: AccessTokenBlacklist,
) {

    /**
     * 이메일이 없든 비밀번호가 틀리든 같은 에러를 돌려준다.
     * (어느 쪽이 틀렸는지 알려주면 가입된 이메일을 수집하는 데 악용될 수 있다)
     */
    fun login(email: String, rawPassword: String): TokenPair {
        val member = memberRepository.findByEmail(email)
            ?.takeIf { passwordEncoder.matches(rawPassword, it.password) }
            ?: throw ServiceException(ErrorCode.INVALID_CREDENTIALS)

        return issueTokens(member)
    }

    /**
     * Refresh Token Rotation (RTR): 한 번 쓴 refresh token 은 즉시 폐기하고 새 토큰 쌍을 준다.
     * consume 이 Redis GETDEL 로 "조회 + 삭제"를 원자적으로 처리하므로,
     * 같은 토큰으로 동시에 두 번 요청해도 한쪽만 성공한다.
     */
    fun reissue(refreshToken: String): TokenPair {
        val memberId = refreshTokenRepository.consume(refreshToken)
            ?: throw ServiceException(ErrorCode.INVALID_REFRESH_TOKEN)
        val member = memberRepository.findByIdOrNull(memberId)
            ?: throw ServiceException(ErrorCode.INVALID_REFRESH_TOKEN)

        return issueTokens(member)
    }

    /** 지금 쓰고 있는 access token 은 블랙리스트에 올리고, 본인 소유의 refresh token 은 삭제한다. */
    fun logout(authMember: AuthMember, refreshToken: String) {
        accessTokenBlacklist.add(authMember.tokenId, authMember.tokenExpiresAt)

        if (refreshTokenRepository.findMemberId(refreshToken) == authMember.memberId) {
            refreshTokenRepository.delete(refreshToken)
        }
    }

    private fun issueTokens(member: Member): TokenPair {
        val refreshToken = jwtTokenProvider.createRefreshToken()
        refreshTokenRepository.save(refreshToken, member.id)

        return TokenPair(
            accessToken = jwtTokenProvider.createAccessToken(member.id, member.role),
            refreshToken = refreshToken,
        )
    }
}
