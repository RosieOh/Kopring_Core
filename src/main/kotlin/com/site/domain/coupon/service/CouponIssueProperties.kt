package com.site.domain.coupon.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * @property directStrategy 즉시 발급(DIRECT)의 동시성 제어 방식. 부하 테스트로 비교한 결과는 load-test/README.md 참고.
 * @property bulkhead 즉시 발급 단계의 동시 실행 제한 (CouponIssueBulkhead)
 */
@ConfigurationProperties(prefix = "coupon.issue")
data class CouponIssueProperties(
    val directStrategy: DirectIssueStrategy = DirectIssueStrategy.ATOMIC_UPDATE,
    val bulkhead: Bulkhead = Bulkhead(),
) {
    /**
     * @property maxConcurrent 발급 단계를 동시에 실행할 수 있는 요청 수. DB 커넥션 풀(기본 10)보다 작게 둬서 다른 API 몫을 남긴다.
     * @property maxWait 자리가 나기를 기다리는 최대 시간. 넘기면 503(COUPON_ISSUE_BUSY).
     *   제한을 두기 전에도 요청은 DB 커넥션을 최대 30초(HikariCP connectionTimeout 기본값) 기다렸다. 같은 시간으로 둬서
     *   제한이 새 실패를 만들지 않고, 기다리는 자리만 커넥션 풀에서 메모리로 옮기게 한다. 줄을 선 순서대로 발급되므로(공정 모드)
     *   짧게 잡으면 쿠폰이 남아 있는데도 줄 뒤쪽이 503 을 받는다.
     */
    data class Bulkhead(
        val maxConcurrent: Int = 5,
        val maxWait: Duration = Duration.ofSeconds(30),
    )
}

enum class DirectIssueStrategy {
    /** DB 가 UPDATE 한 문장으로 "남아 있으면 +1"을 처리한다. 행 잠금을 기다리는 동안 DB 커넥션을 쥔다. */
    ATOMIC_UPDATE,

    /** Redis 락을 잡은 뒤 DB 에서 읽고 판단한다. 락을 기다리는 동안 DB 커넥션은 쓰지 않지만 Redis 왕복이 늘어난다. */
    DISTRIBUTED_LOCK,
}
