package com.site.domain.coupon.service

import com.site.domain.coupon.entity.CouponEvent
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class CouponEventService(
    private val couponEventRepository: CouponEventRepository,
    private val clock: Clock,
) {

    @Transactional
    fun create(command: CouponEventCommand): CouponEvent {
        // 시작 시각이 과거여도 된다 (등록 즉시 시작). 단, 이미 끝난 기간으로는 만들 수 없다.
        val period = command.toPeriod().also { it.requireNotEnded(now()) }

        val couponEvent = CouponEvent(
            name = command.name,
            discount = command.toDiscount(),
            period = period,
            totalQuantity = command.totalQuantity,
        )
        return couponEventRepository.save(couponEvent)
    }

    fun getCouponEvents(pageable: Pageable): Page<CouponEvent> = couponEventRepository.findAll(pageable)

    fun getCouponEvent(couponEventId: Long): CouponEvent =
        couponEventRepository.findByIdOrNull(couponEventId)
            ?: throw ServiceException(ErrorCode.COUPON_EVENT_NOT_FOUND)

    /**
     * save() 를 부르지 않는다. 트랜잭션 안에서 조회한 엔티티의 값을 바꾸면
     * 커밋 시점에 JPA 가 변경을 감지해서(dirty checking) UPDATE 를 날린다.
     */
    @Transactional
    fun update(couponEventId: Long, command: CouponEventCommand): CouponEvent {
        val couponEvent = getCouponEvent(couponEventId)
        val now = now()

        couponEvent.update(
            name = command.name,
            discount = command.toDiscount(),
            period = command.toPeriod().also { it.requireNotEnded(now) },
            totalQuantity = command.totalQuantity,
            now = now,
        )
        return couponEvent
    }

    @Transactional
    fun delete(couponEventId: Long) {
        val couponEvent = getCouponEvent(couponEventId)
        couponEvent.checkModifiable(now())
        couponEventRepository.delete(couponEvent)
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)
}
