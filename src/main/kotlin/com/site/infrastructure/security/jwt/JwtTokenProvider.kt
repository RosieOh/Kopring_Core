package com.site.infrastructure.security.jwt

import com.site.domain.auth.port.TokenIssuer
import com.site.domain.member.entity.MemberRole
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import com.site.infrastructure.security.AuthMember
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.JwtParser
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.UnsupportedJwtException
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey
import io.jsonwebtoken.security.SecurityException as JwtSecurityException

@Component
class JwtTokenProvider(
    private val jwtProperties: JwtProperties,
    private val clock: Clock,
) : TokenIssuer {

    private val secretKey: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.secret))

    // 만료 검증도 서비스 Clock 으로 한다. jjwt 기본값은 시스템 시각이라, 테스트에서 시간을 움직여도 만료 판단은 따라오지 않는다
    private val parser: JwtParser = Jwts.parser().verifyWith(secretKey).clock { Date.from(clock.instant()) }.build()
    private val secureRandom = SecureRandom()

    override fun createAccessToken(memberId: Long, role: MemberRole): String {
        val now = Date.from(clock.instant())
        val expiration = Date(now.time + jwtProperties.accessTokenExpiration.toMillis())

        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(memberId.toString())
            .claim(ROLE_CLAIM, role.name)
            .issuedAt(now)
            .expiration(expiration)
            .signWith(secretKey)
            .compact()
    }

    /**
     * refresh token 은 JWT 가 아니라 무작위 문자열이다.
     * Redis 에 저장된 값과 대조해야만 의미가 있으므로 토큰 안에 정보를 담을 필요가 없고,
     * 탈취되더라도 안에서 읽어낼 정보가 없다.
     */
    override fun createRefreshToken(): String {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES).also { secureRandom.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun parseAccessToken(token: String): AuthMember {
        val claims = try {
            parser.parseSignedClaims(token).payload
        } catch (e: JwtException) {
            throw ServiceException(e.toErrorCode())
        }

        // 서명이 맞아도 이 서비스가 넣는 클레임이 빠졌거나 형식이 다르면 401 로 거절한다. (클레임 구성이 바뀌기 전의 토큰 등)
        // 그대로 꺼내면 NPE·NumberFormatException 이 위의 catch 밖으로 새어, ServiceException 만 잡는 필터를 지나 500 이 된다.
        return claims.toAuthMemberOrNull() ?: throw ServiceException(ErrorCode.INVALID_TOKEN)
    }

    private fun Claims.toAuthMemberOrNull(): AuthMember? {
        val memberId = subject?.toLongOrNull() ?: return null
        // get(name, String::class.java) 는 타입이 다르면 RequiredTypeException 을 던지므로 as? 로 꺼낸다
        val roleName = this[ROLE_CLAIM] as? String ?: return null
        val role = MemberRole.entries.firstOrNull { it.name == roleName } ?: return null
        val tokenId = id ?: return null
        val expiresAt = expiration ?: return null
        return AuthMember(memberId = memberId, role = role, tokenId = tokenId, tokenExpiresAt = expiresAt.toInstant())
    }

    // when + is 로 예외 타입별 분기 (스마트 캐스트)
    private fun JwtException.toErrorCode(): ErrorCode =
        when (this) {
            is ExpiredJwtException -> ErrorCode.EXPIRED_TOKEN
            is UnsupportedJwtException -> ErrorCode.UNSUPPORTED_TOKEN
            is MalformedJwtException, is JwtSecurityException -> ErrorCode.MALFORMED_TOKEN
            else -> ErrorCode.INVALID_TOKEN
        }

    companion object {
        private const val ROLE_CLAIM = "role"
        private const val REFRESH_TOKEN_BYTES = 32
    }
}
