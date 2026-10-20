package com.site.infrastructure.redis

import com.site.infrastructure.security.jwt.JwtProperties
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.util.HexFormat

/**
 * refresh-token:{SHA-256(토큰)} → memberId  (TTL = refresh token 만료 시간)
 *
 * 토큰 원문 대신 해시를 키로 쓴다. Redis 가 유출돼도 키만으로는 토큰을 쓸 수 없다.
 * 기기마다 다른 refresh token 이 생기므로 여러 기기 동시 로그인이 자연스럽게 지원된다.
 */
@Repository
class RefreshTokenRepository(
    private val redisTemplate: StringRedisTemplate,
    private val jwtProperties: JwtProperties,
) {

    fun save(refreshToken: String, memberId: Long) {
        redisTemplate.opsForValue().set(key(refreshToken), memberId.toString(), jwtProperties.refreshTokenExpiration)
    }

    fun findMemberId(refreshToken: String): Long? =
        redisTemplate.opsForValue().get(key(refreshToken))?.toLong()

    /** 조회와 삭제를 Redis GETDEL 한 번으로 처리한다. 같은 토큰은 딱 한 번만 꺼낼 수 있다. */
    fun consume(refreshToken: String): Long? =
        redisTemplate.opsForValue().getAndDelete(key(refreshToken))?.toLong()

    fun delete(refreshToken: String) {
        redisTemplate.delete(key(refreshToken))
    }

    private fun key(refreshToken: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray())
        return KEY_PREFIX + HexFormat.of().formatHex(hash)
    }

    companion object {
        private const val KEY_PREFIX = "refresh-token:"
    }
}
