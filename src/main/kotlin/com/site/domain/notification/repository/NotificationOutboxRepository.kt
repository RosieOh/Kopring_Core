package com.site.domain.notification.repository

import com.site.domain.notification.entity.NotificationOutbox
import com.site.domain.notification.entity.OutboxStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

interface NotificationOutboxRepository : JpaRepository<NotificationOutbox, Long> {

    @Query("select o.id from NotificationOutbox o where o.status = :status and o.nextAttemptAt <= :now order by o.id")
    fun findDueIds(
        status: OutboxStatus,
        now: LocalDateTime,
        pageable: Pageable,
    ): List<Long>

    /**
     * 보낼 차례가 된 PENDING 건만 다음 시도 시각을 lease 만큼 미뤄서 선점한다. 바뀐 행이 1 이면 내가 선점한 것이다.
     * 빠른 경로(커밋 직후)와 스케줄러가 같은 건을 동시에 집어도 한쪽만 보낸다.
     * 보내는 도중 서버가 죽으면 lease 가 지난 뒤 스케줄러가 다시 집어 간다.
     */
    @Modifying
    @Query(
        """
        update NotificationOutbox o
           set o.nextAttemptAt = :leaseUntil
         where o.id = :id
           and o.status = :status
           and o.nextAttemptAt <= :now
        """,
    )
    fun claim(
        id: Long,
        status: OutboxStatus,
        now: LocalDateTime,
        leaseUntil: LocalDateTime,
    ): Int
}
