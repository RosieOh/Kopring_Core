package com.site.infrastructure.security.jwt

import com.site.domain.member.entity.MemberRole
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

class JwtTokenProviderTest : FunSpec({

    val secret = "a29wcmluZy1jb3JlLXRlc3Qtc2VjcmV0LWtleS1mb3ItanVuaXQtb25seS0wMTIzNDU2Nzg5"
    val otherSecret = "b3RoZXIta29wcmluZy1zZWNyZXQta2V5LWZvci1zaWduYXR1cmUtdGVzdC0wMTIzNDU2"

    // 서비스 Clock 을 흉내 낸 시계. now 를 바꾸면 발급 시각과 만료 판단이 함께 움직인다
    val issuedAt = Instant.parse("2026-10-26T03:00:00Z")
    var now = issuedAt
    val clock = mockk<Clock> {
        every { instant() } answers { now }
        every { zone } returns ZoneOffset.UTC
    }

    fun provider(signingKey: String = secret, expiration: Duration = Duration.ofMinutes(30)) =
        JwtTokenProvider(
            JwtProperties(secret = signingKey, accessTokenExpiration = expiration, refreshTokenExpiration = Duration.ofDays(14)),
            clock,
        )

    test("발급한 토큰을 파싱하면 회원 ID 와 권한이 그대로 나온다") {
        val authMember = provider().parseAccessToken(provider().createAccessToken(memberId = 1L, role = MemberRole.ADMIN))

        authMember.memberId shouldBe 1L
        authMember.role shouldBe MemberRole.ADMIN
    }

    test("만료 검증은 서비스 Clock 을 따른다 — 만료 1초 전에는 통과하고, 1초 뒤에는 EXPIRED_TOKEN") {
        val token = provider().createAccessToken(memberId = 1L, role = MemberRole.USER) // 30분짜리

        now = issuedAt + Duration.ofMinutes(30) - Duration.ofSeconds(1)
        provider().parseAccessToken(token).memberId shouldBe 1L

        now = issuedAt + Duration.ofMinutes(30) + Duration.ofSeconds(1)
        shouldThrow<ServiceException> { provider().parseAccessToken(token) }.errorCode shouldBe ErrorCode.EXPIRED_TOKEN
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

    // 같은 키로 서명했지만 클레임 구성이 다른 토큰 (클레임 구성이 바뀌기 전의 토큰, 같은 키를 쓰는 다른 서비스 등)
    fun signedToken(
        subject: String? = "1",
        role: Any? = "USER",
        id: String? = "token-id",
        expiresInSeconds: Long? = 600,
    ): String =
        Jwts.builder()
            .apply { subject?.let { subject(it) } }
            .apply { role?.let { claim("role", it) } }
            .apply { id?.let { id(it) } }
            .apply { expiresInSeconds?.let { expiration(Date.from(now.plusSeconds(it))) } }
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret)))
            .compact()

    context("서명이 맞아도 클레임이 빠졌거나 형식이 다르면 500 이 아니라 INVALID_TOKEN(401)으로 거절한다") {
        withData(
            mapOf(
                "role 클레임 없음" to signedToken(role = null),
                "모르는 role" to signedToken(role = "SUPER"),
                "role 이 문자열이 아님" to signedToken(role = 1),
                "subject 없음" to signedToken(subject = null),
                "subject 가 숫자가 아님" to signedToken(subject = "abc"),
                "토큰 ID(jti) 없음" to signedToken(id = null),
                "만료 시각 없음" to signedToken(expiresInSeconds = null),
            ),
        ) { token ->
            shouldThrow<ServiceException> { provider().parseAccessToken(token) }.errorCode shouldBe ErrorCode.INVALID_TOKEN
        }
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
