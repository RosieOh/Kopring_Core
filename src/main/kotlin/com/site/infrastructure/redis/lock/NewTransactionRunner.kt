package com.site.infrastructure.redis.lock

import org.aspectj.lang.ProceedingJoinPoint
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 락 안에서 실행할 로직을 "항상 새로운" 트랜잭션으로 실행한다.
 *
 * REQUIRED(기본값)였다면 호출한 쪽에 이미 트랜잭션이 있을 때 거기에 합류해 버린다.
 * 그러면 커밋이 바깥 트랜잭션이 끝날 때(= 락을 푼 뒤)로 밀려서, 다음 요청이 커밋 전의 옛 값을 읽게 된다.
 *
 * @Transactional 은 프록시를 통해야 동작하므로 Aspect 안에 메서드로 두지 않고 별도 빈으로 분리했다. (self-invocation 문제)
 */
@Component
class NewTransactionRunner {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun proceed(joinPoint: ProceedingJoinPoint): Any? = joinPoint.proceed()
}
