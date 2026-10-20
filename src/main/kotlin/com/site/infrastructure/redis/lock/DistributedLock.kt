package com.site.infrastructure.redis.lock

import java.util.concurrent.TimeUnit

/**
 * 메서드 실행을 Redis 분산락으로 감싼다. (DistributedLockAspect)
 *
 * 실행 순서: 락 획득 → 새 트랜잭션 시작 → 메서드 실행 → 커밋 → 락 해제
 * 커밋이 락 해제보다 먼저 일어나야, 다음 요청이 커밋된 최신 값을 읽는다.
 *
 * 주의: 이 애너테이션을 붙인 메서드에 @Transactional 을 같이 붙이지 않는다. 트랜잭션은 Aspect 가 락 안쪽에서 연다.
 *
 * @property key 락 이름. SpEL 이며 메서드 파라미터를 #이름 으로 쓸 수 있다. 예) "'coupon-event:' + #couponEventId"
 * @property waitTime 락을 얻기 위해 기다리는 최대 시간. 넘기면 LOCK_ACQUISITION_FAILED.
 * @property leaseTime 락 자동 만료 시간. -1 이면 Redisson watchdog 이 작업이 끝날 때까지 만료를 계속 연장한다.
 *   고정값을 주면 작업이 그보다 오래 걸릴 때 락이 먼저 풀려 버려 동시성 제어가 깨질 수 있다.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DistributedLock(
    val key: String,
    val waitTime: Long = 5,
    val leaseTime: Long = -1,
    val timeUnit: TimeUnit = TimeUnit.SECONDS,
)
