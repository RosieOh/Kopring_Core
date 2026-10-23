package com.site.infrastructure.redis.queue

import com.site.domain.coupon.queue.CouponQueueProperties
import com.site.domain.coupon.queue.CouponQueueResult
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository

/**
 * 쿠폰 대기열의 Redis 저장소.
 *
 * 이벤트별 키 (중괄호는 Redis Cluster 해시 태그 — 같은 이벤트의 키들이 같은 슬롯에 모여서 Lua 스크립트에서 함께 쓸 수 있다)
 * - coupon-queue:{id}:waiting  ZSET  대기 중인 회원. score = 들어온 순번
 * - coupon-queue:{id}:seq      STRING 순번 발급용 카운터
 * - coupon-queue:{id}:result   HASH  memberId → 처리 결과 (ISSUED, SOLD_OUT ...)
 * - coupon-queue:{id}:closed   STRING 마감 사유. 있으면 더 이상 줄을 설 수 없다
 * 전역 키
 * - coupon-queue:active        SET   대기열이 있는 이벤트 ID (워커가 순회할 대상)
 */
@Repository
class CouponQueueRepository(
    private val redisTemplate: StringRedisTemplate,
    private val properties: CouponQueueProperties,
) {

    /**
     * 줄을 세운다. 반환값: 0부터 시작하는 순위, 또는 [ALREADY_PROCESSED] / [CLOSED].
     *
     * "결과 확인 → 마감 확인 → 이미 대기 중인지 확인 → 순번 발급 → 추가"를 Lua 로 묶어 Redis 안에서 한 번에 실행한다.
     * Redis 는 스크립트 하나를 실행하는 동안 다른 명령을 끼워 넣지 않으므로, 여러 서버에서 동시에 불러도 순번이 겹치거나 빠지지 않는다.
     */
    fun enqueue(couponEventId: Long, memberId: Long): Long {
        val keys = listOf(waitingKey(couponEventId), seqKey(couponEventId), resultKey(couponEventId), closedKey(couponEventId))
        return requireNotNull(
            redisTemplate.execute(ENQUEUE_SCRIPT, keys, memberId.toString(), properties.keyTtl.seconds.toString()),
        )
    }

    fun activate(couponEventId: Long) {
        redisTemplate.opsForSet().add(ACTIVE_KEY, couponEventId.toString())
    }

    fun activeEventIds(): Set<Long> = redisTemplate.opsForSet().members(ACTIVE_KEY).orEmpty().map { it.toLong() }.toSet()

    fun deactivate(couponEventId: Long) {
        redisTemplate.opsForSet().remove(ACTIVE_KEY, couponEventId.toString())
    }

    /** 비어 있으면 순회 대상에서 뺀다. 빼는 사이에 누가 들어왔다면 다시 넣는다. */
    fun deactivateIfEmpty(couponEventId: Long) {
        deactivate(couponEventId)
        if (waitingCount(couponEventId) > 0) {
            activate(couponEventId)
        }
    }

    /** 앞에서부터 count 명을 꺼낸다. ZPOPMIN 은 꺼내기와 삭제가 원자적이라 서버가 여러 대여도 한 명을 두 번 꺼내지 않는다. */
    fun popWaiting(couponEventId: Long, count: Long): List<Long> =
        redisTemplate.opsForZSet().popMin(waitingKey(couponEventId), count).orEmpty()
            .sortedBy { it.score }
            .mapNotNull { it.value?.toLong() }

    fun waitingCount(couponEventId: Long): Long = redisTemplate.opsForZSet().zCard(waitingKey(couponEventId)) ?: 0

    fun rank(couponEventId: Long, memberId: Long): Long? = redisTemplate.opsForZSet().rank(waitingKey(couponEventId), memberId.toString())

    fun saveResults(
        couponEventId: Long,
        memberIds: List<Long>,
        result: CouponQueueResult,
    ) {
        if (memberIds.isEmpty()) return
        val key = resultKey(couponEventId)
        redisTemplate.opsForHash<String, String>().putAll(key, memberIds.associate { it.toString() to result.name })
        redisTemplate.expire(key, properties.keyTtl)
    }

    fun findResult(couponEventId: Long, memberId: Long): CouponQueueResult? =
        redisTemplate.opsForHash<String, String>().get(resultKey(couponEventId), memberId.toString())
            ?.let { CouponQueueResult.valueOf(it) }

    fun close(couponEventId: Long, reason: CouponQueueResult) {
        redisTemplate.opsForValue().set(closedKey(couponEventId), reason.name, properties.keyTtl)
    }

    fun closedReason(couponEventId: Long): CouponQueueResult? =
        redisTemplate.opsForValue().get(closedKey(couponEventId))?.let { CouponQueueResult.valueOf(it) }

    private fun waitingKey(id: Long) = "coupon-queue:{$id}:waiting"
    private fun seqKey(id: Long) = "coupon-queue:{$id}:seq"
    private fun resultKey(id: Long) = "coupon-queue:{$id}:result"
    private fun closedKey(id: Long) = "coupon-queue:{$id}:closed"

    companion object {
        const val ALREADY_PROCESSED = -2L
        const val CLOSED = -1L
        private const val ACTIVE_KEY = "coupon-queue:active"

        // KEYS: waiting, seq, result, closed / ARGV: memberId, ttlSeconds
        private val ENQUEUE_SCRIPT = DefaultRedisScript(
            """
            if redis.call('HEXISTS', KEYS[3], ARGV[1]) == 1 then
              return -2
            end
            if redis.call('EXISTS', KEYS[4]) == 1 then
              return -1
            end
            local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
            if rank then
              return rank
            end
            local seq = redis.call('INCR', KEYS[2])
            redis.call('ZADD', KEYS[1], seq, ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            return redis.call('ZRANK', KEYS[1], ARGV[1])
            """.trimIndent(),
            // Long::class.java 는 원시 타입 long 을 가리킨다. 래퍼 클래스(java.lang.Long)가 필요해서 javaObjectType 을 쓴다.
            Long::class.javaObjectType,
        )
    }
}
