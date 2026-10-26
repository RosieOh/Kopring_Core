package com.site.infrastructure.security.jwt

import com.site.domain.member.entity.MemberRole
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import java.time.Duration

class JwtTokenProviderTest : FunSpec({

    val secret = "a29wcmluZy1jb3JlLXRlc3Qtc2VjcmV0LWtleS1mb3ItanVuaXQtb25seS0wMTIzNDU2Nzg5"
    val otherSecret = "b3RoZXIta29wcmluZy1zZWNyZXQta2V5LWZvci1zaWduYXR1cmUtdGVzdC0wMTIzNDU2"

    fun provider(signingKey: String = secret, expiration: Duration = Duration.ofMinutes(30)) =
        JwtTokenProvider(JwtProperties(secret = signingKey, accessTokenExpiration = expiration, refreshTokenExpiration = Duration.ofDays(14)))

    test("발급한 토큰을 파싱하면 회원 ID 와 권한이 그대로 나온다") {
        val authMember = provider().parseAccessToken(provider().createAccessToken(memberId = 1L, role = MemberRole.ADMIN))

        authMember.memberId shouldBe 1L
        authMember.role shouldBe MemberRole.ADMIN
    }

    test("access token 마다 고유한 토큰 ID 가 붙는다 (로그아웃 블랙리스트의 키)") {
        val first = provider().run { parseAccessToken(createAccessToken(1L, MemberRole.USER)) }
        val second = provider().run { parseAccessToken(createAccessToken(1L, MemberRole.USER)) }

        first.tokenId shouldNotBe second.tokenId
    }

    test("refresh token 은 매번 다른 URL-safe 무작위 문자열이다") {
        val first = provider().createRefreshToken()

        first shouldMatch Regex("^[A-Za-z0-9_-]{43}$") // 32바이트 → Base64 URL-safe, 패딩 없음
        first shouldNotBe provider().createRefreshToken()
    }

    context("받아들이지 않는 토큰은 이유에 맞는 에러 코드로 거절한다") {
        withData(
            mapOf(
                "만료된 토큰 → EXPIRED_TOKEN" to
                    (provider(expiration = Duration.ofSeconds(-1)).createAccessToken(1L, MemberRole.USER) to ErrorCode.EXPIRED_TOKEN),
                "다른 키로 서명된 토큰 → MALFORMED_TOKEN" to
                    (provider(signingKey = otherSecret).createAccessToken(1L, MemberRole.USER) to ErrorCode.MALFORMED_TOKEN),
                "JWT 형식이 아닌 문자열 → MALFORMED_TOKEN" to ("not-a-jwt" to ErrorCode.MALFORMED_TOKEN),
            ),
        ) { (token, expected) ->
            shouldThrow<ServiceException> { provider().parseAccessToken(token) }.errorCode shouldBe expected
        }
    }
})
