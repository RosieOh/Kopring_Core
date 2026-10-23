package com.site.infrastructure.redis

import com.site.domain.auth.port.ConsumeResult
import com.site.domain.auth.port.RefreshTokenOwner
import com.site.domain.auth.port.RefreshTokenStore
import com.site.infrastructure.security.jwt.JwtProperties
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.util.HexFormat

/**
 * - refresh-token:{해시}              → "memberId:familyId"  (아직 안 쓴 토큰)
 * - refresh-token:{해시}:used         → "memberId:familyId"  (이미 쓴 토큰 — 재사용 감지용)
 * - refresh-token-family:{familyId}:revoked → "1"           (폐기된 패밀리)
 * 모두 TTL = refresh token 만료 시간.
 *
 * 토큰 원문 대신 SHA-256 해시를 키로 쓴다. Redis 가 유출돼도 키만으로는 토큰을 쓸 수 없다.
 * 중괄호는 Redis Cluster 해시 태그 — 같은 토큰의 두 키가 같은 슬롯에 있어야 Lua 에서 함께 쓸 수 있다.
 */
@Repository
class RedisRefreshTokenStore(
    private val redisTemplate: StringRedisTemplate,
    private val jwtProperties: JwtProperties,
) : RefreshTokenStore {

    override fun save(refreshToken: String, owner: RefreshTokenOwner) {
        redisTemplate.opsForValue().set(tokenKey(hash(refreshToken)), owner.serialize(), jwtProperties.refreshTokenExpiration)
    }

    override fun findOwner(refreshToken: String): RefreshTokenOwner? = redisTemplate.opsForValue().get(tokenKey(hash(refreshToken)))?.toOwner()

    override fun consume(refreshToken: String): ConsumeResult {
        val hash = hash(refreshToken)
        val result = redisTemplate.execute(
            CONSUME_SCRIPT,
            listOf(tokenKey(hash), usedKey(hash)),
            jwtProperties.refreshTokenExpiration.seconds.toString(),
        ) ?: return ConsumeResult.Invalid

        val (status, owner) = result.split(STATUS_SEPARATOR, limit = 2)
        return when (status) {
            CONSUMED -> ConsumeResult.Consumed(owner.toOwner())
            REUSED -> ConsumeResult.Reused(owner.toOwner())
            else -> error("알 수 없는 consume 결과: $result")
        }
    }

    override fun revokeFamily(familyId: String) {
        redisTemplate.opsForValue().set(revokedFamilyKey(familyId), "1", jwtProperties.refreshTokenExpiration)
    }

    override fun isFamilyRevoked(familyId: String): Boolean = redisTemplate.hasKey(revokedFamilyKey(familyId)) == true

    private fun hash(refreshToken: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray()))

    private fun tokenKey(hash: String) = "refresh-token:{$hash}"

    private fun usedKey(hash: String) = "refresh-token:{$hash}:used"

    private fun revokedFamilyKey(familyId: String) = "refresh-token-family:{$familyId}:revoked"

    private fun RefreshTokenOwner.serialize() = "$memberId$OWNER_SEPARATOR$familyId"

    private fun String.toOwner(): RefreshTokenOwner {
        val (memberId, familyId) = split(OWNER_SEPARATOR, limit = 2)
        return RefreshTokenOwner(memberId = memberId.toLong(), familyId = familyId)
    }

    companion object {
        private const val OWNER_SEPARATOR = ":"
        private const val STATUS_SEPARATOR = "|"
        private const val CONSUMED = "CONSUMED"
        private const val REUSED = "REUSED"

        // KEYS: 토큰 키, 사용됨 키 / ARGV: TTL(초)
        // 아직 안 쓴 토큰이면 꺼내면서 "사용됨"으로 옮기고, 이미 쓴 토큰이면 그 주인을 알려준다.
        private val CONSUME_SCRIPT = DefaultRedisScript(
            """
            local owner = redis.call('GETDEL', KEYS[1])
            if owner then
              redis.call('SET', KEYS[2], owner, 'EX', ARGV[1])
              return 'CONSUMED|' .. owner
            end
            local used = redis.call('GET', KEYS[2])
            if used then
              return 'REUSED|' .. used
            end
            return nil
            """.trimIndent(),
            String::class.java,
        )
    }
}
