package com.site.domain.auth.service

import com.site.domain.auth.port.AccessTokenBlacklist
import com.site.domain.auth.port.RefreshTokenStore
import com.site.domain.auth.port.TokenIssuer
import com.site.domain.member.entity.Member
import com.site.domain.member.repository.MemberRepository
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 토큰 발급·저장·블랙리스트는 모두 포트(인터페이스)로만 다룬다. JWT 인지, Redis 에 저장하는지는 이 클래스가 모른다.
 */
@Service
@Transactional(readOnly = true)
class AuthService(
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenIssuer: TokenIssuer,
    private val refreshTokenStore: RefreshTokenStore,
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
     * consume 이 조회와 삭제를 원자적으로 처리하므로, 같은 토큰으로 동시에 두 번 요청해도 한쪽만 성공한다.
     */
    fun reissue(refreshToken: String): TokenPair {
        val memberId = refreshTokenStore.consume(refreshToken)
            ?: throw ServiceException(ErrorCode.INVALID_REFRESH_TOKEN)
        val member = memberRepository.findByIdOrNull(memberId)
            ?: throw ServiceException(ErrorCode.INVALID_REFRESH_TOKEN)

        return issueTokens(member)
    }

    /** 지금 쓰고 있는 access token 은 블랙리스트에 올리고, 본인 소유의 refresh token 은 삭제한다. */
    fun logout(
        memberId: Long,
        accessTokenId: String,
        accessTokenExpiresAt: Instant,
        refreshToken: String,
    ) {
        accessTokenBlacklist.add(accessTokenId, accessTokenExpiresAt)

        if (refreshTokenStore.findMemberId(refreshToken) == memberId) {
            refreshTokenStore.delete(refreshToken)
        }
    }

    private fun issueTokens(member: Member): TokenPair {
        val refreshToken = tokenIssuer.createRefreshToken()
        refreshTokenStore.save(refreshToken, member.id)

        return TokenPair(
            accessToken = tokenIssuer.createAccessToken(member.id, member.role),
            refreshToken = refreshToken,
        )
    }
}
