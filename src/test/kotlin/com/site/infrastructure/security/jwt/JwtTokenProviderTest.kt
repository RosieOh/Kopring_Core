package com.site.infrastructure.security.jwt

import com.site.domain.member.entity.MemberRole
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class JwtTokenProviderTest {

    private val secret = "a29wcmluZy1jb3JlLXRlc3Qtc2VjcmV0LWtleS1mb3ItanVuaXQtb25seS0wMTIzNDU2Nzg5"

    private fun provider(secret: String = this.secret, expiration: Duration = Duration.ofMinutes(30)) =
        JwtTokenProvider(
            JwtProperties(
                secret = secret,
                accessTokenExpiration = expiration,
                refreshTokenExpiration = Duration.ofDays(14),
            ),
        )

    @Test
    fun `발급한 토큰을 파싱하면 회원 ID와 권한이 그대로 나온다`() {
        val token = provider().createAccessToken(memberId = 1L, role = MemberRole.ADMIN)

        val authMember = provider().parseAccessToken(token)

        assertEquals(1L, authMember.memberId)
        assertEquals(MemberRole.ADMIN, authMember.role)
    }

    @Test
    fun `access token 마다 고유한 토큰 ID가 붙는다`() {
        val first = provider().run { parseAccessToken(createAccessToken(1L, MemberRole.USER)) }
        val second = provider().run { parseAccessToken(createAccessToken(1L, MemberRole.USER)) }

        assertNotEquals(first.tokenId, second.tokenId)
    }

    @Test
    fun `만료된 토큰은 EXPIRED_TOKEN`() {
        val token = provider(expiration = Duration.ofSeconds(-1)).createAccessToken(1L, MemberRole.USER)

        val exception = assertThrows<ServiceException> { provider().parseAccessToken(token) }

        assertEquals(ErrorCode.EXPIRED_TOKEN, exception.errorCode)
    }

    @Test
    fun `다른 키로 서명된 토큰은 MALFORMED_TOKEN`() {
        val otherSecret = "b3RoZXIta29wcmluZy1zZWNyZXQta2V5LWZvci1zaWduYXR1cmUtdGVzdC0wMTIzNDU2"
        val token = provider(secret = otherSecret).createAccessToken(1L, MemberRole.USER)

        val exception = assertThrows<ServiceException> { provider().parseAccessToken(token) }

        assertEquals(ErrorCode.MALFORMED_TOKEN, exception.errorCode)
    }

    @Test
    fun `JWT 형식이 아닌 문자열은 MALFORMED_TOKEN`() {
        val exception = assertThrows<ServiceException> { provider().parseAccessToken("not-a-jwt") }

        assertEquals(ErrorCode.MALFORMED_TOKEN, exception.errorCode)
    }
}
