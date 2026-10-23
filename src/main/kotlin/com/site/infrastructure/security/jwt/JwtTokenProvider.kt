package com.site.infrastructure.security.jwt

import com.site.domain.member.entity.MemberRole
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import com.site.infrastructure.security.AuthMember
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
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey
import io.jsonwebtoken.security.SecurityException as JwtSecurityException

@Component
class JwtTokenProvider(
    private val jwtProperties: JwtProperties,
) {

    private val secretKey: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.secret))
    private val parser: JwtParser = Jwts.parser().verifyWith(secretKey).build()
    private val secureRandom = SecureRandom()

    fun createAccessToken(memberId: Long, role: MemberRole): String {
        val now = Date()
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
    fun createRefreshToken(): String {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES).also { secureRandom.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun parseAccessToken(token: String): AuthMember {
        val claims = try {
            parser.parseSignedClaims(token).payload
        } catch (e: JwtException) {
            throw ServiceException(e.toErrorCode())
        }

        return AuthMember(
            memberId = claims.subject.toLong(),
            role = MemberRole.valueOf(claims.get(ROLE_CLAIM, String::class.java)),
            tokenId = claims.id,
            tokenExpiresAt = claims.expiration.toInstant(),
        )
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
