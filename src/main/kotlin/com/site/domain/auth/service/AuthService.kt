package com.site.domain.auth.service

import com.site.domain.auth.port.AccessTokenBlacklist
import com.site.domain.auth.port.ConsumeResult
import com.site.domain.auth.port.RefreshTokenOwner
import com.site.domain.auth.port.RefreshTokenStore
import com.site.domain.auth.port.TokenIssuer
import com.site.domain.member.entity.Member
import com.site.domain.member.repository.MemberRepository
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

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

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이메일이 없든 비밀번호가 틀리든 같은 에러를 돌려준다.
     * (어느 쪽이 틀렸는지 알려주면 가입된 이메일을 수집하는 데 악용될 수 있다)
     */
    fun login(email: String, rawPassword: String): TokenPair {
        val member = memberRepository.findByEmail(email)
            ?.takeIf { passwordEncoder.matches(rawPassword, it.password) }
            ?: throw ServiceException(ErrorCode.INVALID_CREDENTIALS)

        // 로그인 한 번 = 새 토큰 패밀리
        return issueTokens(member, familyId = UUID.randomUUID().toString())
    }

    /**
     * Refresh Token Rotation (RTR) + 재사용 감지.
     *
     * - 정상: 쓴 토큰은 "사용됨"으로 바뀌고, 같은 패밀리로 새 토큰 쌍을 준다.
     * - 이미 쓴 토큰이 다시 옴: 정상 사용자는 이미 새 토큰을 받았으므로 누군가 훔쳐 쓴 것이다.
     *   패밀리 전체를 폐기해서 공격자와 사용자 모두 다시 로그인하게 한다. (다른 기기의 로그인은 다른 패밀리라 유지)
     * - 폐기된 패밀리의 토큰: 재사용이 감지된 뒤 공격자가 받아 간 새 토큰까지 여기서 막힌다.
     */
    fun reissue(refreshToken: String): TokenPair {
        val owner = when (val result = refreshTokenStore.consume(refreshToken)) {
            is ConsumeResult.Consumed -> result.owner
            is ConsumeResult.Reused -> {
                refreshTokenStore.revokeFamily(result.owner.familyId)
                log.warn("Refresh Token 재사용 감지 — 탈취 의심으로 토큰 패밀리를 폐기합니다. {}", result.owner)
                throw ServiceException(ErrorCode.REFRESH_TOKEN_REUSED)
            }
            ConsumeResult.Invalid -> throw ServiceException(ErrorCode.INVALID_REFRESH_TOKEN)
        }

        if (refreshTokenStore.isFamilyRevoked(owner.familyId)) {
            throw ServiceException(ErrorCode.INVALID_REFRESH_TOKEN)
        }
        val member = memberRepository.findByIdOrNull(owner.memberId)
            ?: throw ServiceException(ErrorCode.INVALID_REFRESH_TOKEN)

        return issueTokens(member, familyId = owner.familyId)
    }

    /** 지금 쓰고 있는 access token 은 블랙리스트에 올리고, 본인 소유의 refresh token 패밀리는 폐기한다. */
    fun logout(
        memberId: Long,
        accessTokenId: String,
        accessTokenExpiresAt: Instant,
        refreshToken: String,
    ) {
        accessTokenBlacklist.add(accessTokenId, accessTokenExpiresAt)

        refreshTokenStore.findOwner(refreshToken)
            ?.takeIf { it.memberId == memberId }
            ?.let { refreshTokenStore.revokeFamily(it.familyId) }
    }

    private fun issueTokens(member: Member, familyId: String): TokenPair {
        val refreshToken = tokenIssuer.createRefreshToken()
        refreshTokenStore.save(refreshToken, RefreshTokenOwner(memberId = member.id, familyId = familyId))

        return TokenPair(
            accessToken = tokenIssuer.createAccessToken(member.id, member.role),
            refreshToken = refreshToken,
        )
    }
}
