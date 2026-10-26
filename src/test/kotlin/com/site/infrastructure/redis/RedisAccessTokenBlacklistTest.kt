package com.site.infrastructure.redis

import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RedisAccessTokenBlacklistTest : FunSpec({

    val now = Instant.parse("2026-10-26T03:00:00Z")
    val valueOperations = mockk<ValueOperations<String, String>>(relaxed = true)
    val redisTemplate = mockk<StringRedisTemplate> { every { opsForValue() } returns valueOperations }
    val blacklist = RedisAccessTokenBlacklist(redisTemplate, Clock.fixed(now, ZoneOffset.UTC))

    test("원래 만료 시각까지 남은 시간만큼만 블랙리스트에 둔다 (서비스 Clock 기준)") {
        blacklist.add("token-id", expiresAt = now + Duration.ofMinutes(10))

        verify { valueOperations.set("access-token:blacklist:token-id", "logout", Duration.ofMinutes(10)) }
    }

    test("이미 만료된 토큰은 넣지 않는다 — 만료 검증에서 어차피 걸린다") {
        blacklist.add("expired", expiresAt = now)
        blacklist.add("long-expired", expiresAt = now - Duration.ofSeconds(1))

        verify(exactly = 0) { valueOperations.set(any(), any(), any<Duration>()) }
    }
})
