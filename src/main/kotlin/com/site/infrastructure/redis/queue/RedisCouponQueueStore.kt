package com.site.infrastructure.redis.queue

import com.site.domain.coupon.queue.CouponQueueProperties
import com.site.domain.coupon.queue.CouponQueueResult
import com.site.domain.coupon.queue.port.CouponQueueStore
import com.site.domain.coupon.queue.port.EnqueueResult
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository

/**
 * 쿠폰 대기열의 Redis 저장소.
 *
 * 이벤트별 키 (중괄호는 Redis Cluster 해시 태그 — 같은 이벤트의 키들이 같은 슬롯에 모여서 Lua 스크립트에서 함께 쓸 수 있다)
 * - coupon-queue:{id}:waiting     ZSET   대기 중. score = 들어온 순번
 * - coupon-queue:{id}:processing  ZSET   처리 중. score = lease 만료 시각(epoch ms)
 * - coupon-queue:{id}:seq         STRING 순번 발급용 카운터
 * - coupon-queue:{id}:result      HASH   memberId → 처리 결과
 * - coupon-queue:{id}:closed      STRING 마감 사유. 있으면 더 이상 줄을 설 수 없다
 * 전역 키
 * - coupon-queue:active           SET    대기열이 있는 이벤트 ID (워커가 순회할 대상)
 *
 * 상태를 옮기는 연산은 모두 Lua 스크립트 하나로 실행한다. Redis 는 스크립트 실행 중에 다른 명령을 끼워 넣지 않으므로
 * "꺼냈는데 어디에도 기록되지 않은" 중간 상태가 생기지 않는다.
 */
@Repository
class RedisCouponQueueStore(
    private val redisTemplate: StringRedisTemplate,
    private val properties: CouponQueueProperties,
) : CouponQueueStore {

    private val ttlSeconds: String
        get() = properties.keyTtl.seconds.toString()

    override fun enqueue(couponEventId: Long, memberId: Long): EnqueueResult {
        val keys = listOf(
            waitingKey(couponEventId),
            seqKey(couponEventId),
            resultKey(couponEventId),
            closedKey(couponEventId),
            processingKey(couponEventId),
        )
        val code = requireNotNull(redisTemplate.execute(ENQUEUE_SCRIPT, keys, memberId.toString(), ttlSeconds))
        return when (code) {
            ALREADY_PROCESSED -> EnqueueResult.AlreadyProcessed
            CLOSED -> EnqueueResult.Closed
            else -> EnqueueResult.Enqueued(rank = code)
        }
    }

    override fun activate(couponEventId: Long) {
        redisTemplate.opsForSet().add(ACTIVE_KEY, couponEventId.toString())
    }

    override fun activeEventIds(): Set<Long> = redisTemplate.opsForSet().members(ACTIVE_KEY).orEmpty().map { it.toLong() }.toSet()

    override fun deactivate(couponEventId: Long) {
        redisTemplate.opsForSet().remove(ACTIVE_KEY, couponEventId.toString())
    }

    override fun deactivateIfEmpty(couponEventId: Long) {
        deactivate(couponEventId)
        if (count(waitingKey(couponEventId)) > 0 || count(processingKey(couponEventId)) > 0) {
            activate(couponEventId)
        }
    }

    override fun claimNext(
        couponEventId: Long,
        count: Long,
        leaseUntilMillis: Long,
    ): List<Long> {
        if (count <= 0) return emptyList()
        val keys = listOf(waitingKey(couponEventId), processingKey(couponEventId))
        return redisTemplate.execute(CLAIM_NEXT_SCRIPT, keys, count.toString(), leaseUntilMillis.toString(), ttlSeconds).toMemberIds()
    }

    override fun claimExpired(
        couponEventId: Long,
        nowMillis: Long,
        count: Long,
        leaseUntilMillis: Long,
    ): List<Long> {
        val keys = listOf(processingKey(couponEventId))
        return redisTemplate.execute(CLAIM_EXPIRED_SCRIPT, keys, nowMillis.toString(), count.toString(), leaseUntilMillis.toString())
            .toMemberIds()
    }

    override fun complete(
        couponEventId: Long,
        memberIds: List<Long>,
        result: CouponQueueResult,
    ) {
        if (memberIds.isEmpty()) return
        val keys = listOf(resultKey(couponEventId), processingKey(couponEventId))
        val args = listOf(result.name, ttlSeconds) + memberIds.map { it.toString() }
        redisTemplate.execute(COMPLETE_SCRIPT, keys, *args.toTypedArray())
    }

    override fun drainWaiting(
        couponEventId: Long,
        count: Long,
        result: CouponQueueResult,
    ): Long {
        val keys = listOf(waitingKey(couponEventId), resultKey(couponEventId))
        return redisTemplate.execute(DRAIN_SCRIPT, keys, count.toString(), result.name, ttlSeconds) ?: 0
    }

    override fun rank(couponEventId: Long, memberId: Long): Long? = redisTemplate.opsForZSet().rank(waitingKey(couponEventId), memberId.toString())

    override fun isProcessing(couponEventId: Long, memberId: Long): Boolean =
        redisTemplate.opsForZSet().score(processingKey(couponEventId), memberId.toString()) != null

    override fun findResult(couponEventId: Long, memberId: Long): CouponQueueResult? =
        redisTemplate.opsForHash<String, String>().get(resultKey(couponEventId), memberId.toString())
            ?.let { CouponQueueResult.valueOf(it) }

    override fun close(couponEventId: Long, reason: CouponQueueResult) {
        redisTemplate.opsForValue().set(closedKey(couponEventId), reason.name, properties.keyTtl)
    }

    override fun closedReason(couponEventId: Long): CouponQueueResult? =
        redisTemplate.opsForValue().get(closedKey(couponEventId))?.let { CouponQueueResult.valueOf(it) }

    private fun count(key: String): Long = redisTemplate.opsForZSet().zCard(key) ?: 0

    private fun List<*>?.toMemberIds(): List<Long> = orEmpty().map { it.toString().toLong() }

    private fun waitingKey(id: Long) = "coupon-queue:{$id}:waiting"

    private fun processingKey(id: Long) = "coupon-queue:{$id}:processing"

    private fun seqKey(id: Long) = "coupon-queue:{$id}:seq"

    private fun resultKey(id: Long) = "coupon-queue:{$id}:result"

    private fun closedKey(id: Long) = "coupon-queue:{$id}:closed"

    companion object {
        private const val ACTIVE_KEY = "coupon-queue:active"

        // ENQUEUE_SCRIPT 가 돌려주는 특수 값
        private const val ALREADY_PROCESSED = -2L
        private const val CLOSED = -1L

        // KEYS: waiting, seq, result, closed, processing / ARGV: memberId, ttlSeconds
        private val ENQUEUE_SCRIPT = DefaultRedisScript(
            """
            if redis.call('HEXISTS', KEYS[3], ARGV[1]) == 1 then
              return -2
            end
            if redis.call('ZSCORE', KEYS[5], ARGV[1]) then
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

        // KEYS: waiting, processing / ARGV: count, leaseUntil, ttlSeconds
        // ZPOPMIN 은 [회원, 점수, 회원, 점수, ...] 를 순번 순서로 돌려준다
        private val CLAIM_NEXT_SCRIPT = DefaultRedisScript(
            """
            local popped = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
            local members = {}
            for i = 1, #popped, 2 do
              members[#members + 1] = popped[i]
              redis.call('ZADD', KEYS[2], ARGV[2], popped[i])
            end
            if #members > 0 then
              redis.call('EXPIRE', KEYS[2], ARGV[3])
            end
            return members
            """.trimIndent(),
            List::class.java,
        )

        // KEYS: processing / ARGV: now, count, leaseUntil
        private val CLAIM_EXPIRED_SCRIPT = DefaultRedisScript(
            """
            local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            for _, member in ipairs(expired) do
              redis.call('ZADD', KEYS[1], 'XX', ARGV[3], member)
            end
            return expired
            """.trimIndent(),
            List::class.java,
        )

        // KEYS: result, processing / ARGV: result, ttlSeconds, memberId...
        private val COMPLETE_SCRIPT = DefaultRedisScript(
            """
            for i = 3, #ARGV do
              redis.call('HSET', KEYS[1], ARGV[i], ARGV[1])
              redis.call('ZREM', KEYS[2], ARGV[i])
            end
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return #ARGV - 2
            """.trimIndent(),
            Long::class.javaObjectType,
        )

        // KEYS: waiting, result / ARGV: count, result, ttlSeconds
        private val DRAIN_SCRIPT = DefaultRedisScript(
            """
            local popped = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
            for i = 1, #popped, 2 do
              redis.call('HSET', KEYS[2], popped[i], ARGV[2])
            end
            if #popped > 0 then
              redis.call('EXPIRE', KEYS[2], ARGV[3])
            end
            return #popped / 2
            """.trimIndent(),
            Long::class.javaObjectType,
        )
    }
}
