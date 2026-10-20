package com.site.infrastructure.redis

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * access-token:blacklist:{jti} → "logout"
 *
 * 로그아웃한 access token 을 원래 만료 시각까지만 블랙리스트에 둔다.
 * 그 이후에는 어차피 만료 검증에서 걸리므로 TTL 로 Redis 가 알아서 지우게 한다.
 */
@Component
class AccessTokenBlacklist(
    private val redisTemplate: StringRedisTemplate,
) {

    fun add(tokenId: String, expiresAt: Instant) {
        val ttl = Duration.between(Instant.now(), expiresAt)
        if (ttl.isNegative || ttl.isZero) return

        redisTemplate.opsForValue().set(key(tokenId), LOGOUT_VALUE, ttl)
    }

    fun contains(tokenId: String): Boolean =
        redisTemplate.hasKey(key(tokenId)) == true

    private fun key(tokenId: String) = "$KEY_PREFIX$tokenId"

    companion object {
        private const val KEY_PREFIX = "access-token:blacklist:"
        private const val LOGOUT_VALUE = "logout"
    }
}
