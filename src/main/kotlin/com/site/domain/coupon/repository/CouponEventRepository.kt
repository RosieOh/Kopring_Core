package com.site.domain.coupon.repository

import com.site.domain.coupon.entity.CouponEvent
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

interface CouponEventRepository : JpaRepository<CouponEvent, Long> {

    /** 아직 끝나지 않은(진행 중 + 시작 전) 이벤트 */
    @Query("select e from CouponEvent e where e.period.endAt > :now")
    fun findNotEnded(now: LocalDateTime, pageable: Pageable): Page<CouponEvent>

    /**
     * "발급 가능할 때만 +1"을 UPDATE 한 문장으로 처리한다. 반환값은 바뀐 행 수(0 또는 1)다.
     *
     * - 영속성 컨텍스트를 거치지 않는 벌크 연산이라, 같은 트랜잭션에서 미리 읽어 둔 CouponEvent 가 있다면 그 값은 낡은 값이 된다.
     * - 엔티티 리스너를 거치지 않으므로 updatedAt(@LastModifiedDate)도 갱신되지 않는다.
     */
    @Modifying
    @Query(
        """
        update CouponEvent e
           set e.issuedQuantity = e.issuedQuantity + 1
         where e.id = :id
           and e.issuedQuantity < e.totalQuantity
           and e.period.startAt <= :now
           and e.period.endAt > :now
        """,
    )
    fun increaseIssuedQuantity(id: Long, now: LocalDateTime): Int
}
