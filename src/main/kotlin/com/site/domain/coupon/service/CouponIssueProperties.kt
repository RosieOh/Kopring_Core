package com.site.domain.coupon.service

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * @property directStrategy 즉시 발급(DIRECT)의 동시성 제어 방식. 부하 테스트로 비교한 결과는 load-test/README.md 참고.
 */
@ConfigurationProperties(prefix = "coupon.issue")
data class CouponIssueProperties(
    val directStrategy: DirectIssueStrategy = DirectIssueStrategy.ATOMIC_UPDATE,
)

enum class DirectIssueStrategy {
    /** DB 가 UPDATE 한 문장으로 "남아 있으면 +1"을 처리한다. 행 잠금을 기다리는 동안 DB 커넥션을 쥔다. */
    ATOMIC_UPDATE,

    /** Redis 락을 잡은 뒤 DB 에서 읽고 판단한다. 락을 기다리는 동안 DB 커넥션은 쓰지 않지만 Redis 왕복이 늘어난다. */
    DISTRIBUTED_LOCK,
}
