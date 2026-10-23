package com.site.domain.coupon.service

import com.site.domain.coupon.entity.CouponEvent
import com.site.domain.coupon.entity.IssuedCoupon
import com.site.domain.coupon.repository.CouponEventRepository
import com.site.domain.coupon.repository.IssuedCouponRepository
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/** 내가 받은 쿠폰: 조회와 사용 */
@Service
@Transactional(readOnly = true)
class CouponWalletService(
    private val issuedCouponRepository: IssuedCouponRepository,
    private val couponEventRepository: CouponEventRepository,
    private val clock: Clock,
) {

    /**
     * 쿠폰과 그 이벤트 정보를 함께 돌려준다.
     * 쿠폰마다 이벤트를 따로 조회하면 쿠폰 수만큼 쿼리가 나간다(N+1). 이벤트 ID 를 모아 한 번에 조회한다.
     */
    fun getMyCoupons(memberId: Long, pageable: Pageable): Page<OwnedCoupon> {
        val coupons = issuedCouponRepository.findByMemberId(memberId, pageable)
        val events = couponEventRepository.findAllById(coupons.content.map { it.couponEventId }.toSet())
            .associateBy { it.id }

        return coupons.map { coupon -> OwnedCoupon(coupon, requireNotNull(events[coupon.couponEventId])) }
    }

    /**
     * 쿠폰을 쓴다. 같은 쿠폰으로 동시에 두 번 요청하면 먼저 커밋한 쪽만 성공한다. (낙관적 락)
     *
     * saveAndFlush 로 이 메서드 안에서 UPDATE 를 실행시킨다. 커밋 시점까지 미루면 버전 충돌 예외가
     * 메서드가 끝난 뒤(트랜잭션 프록시)에서 터져서 "이미 사용한 쿠폰"으로 바꿔 줄 수 없다.
     */
    @Transactional
    fun use(
        memberId: Long,
        issuedCouponId: Long,
        orderAmount: Int,
    ): CouponUsage {
        val coupon = issuedCouponRepository.findByIdOrNull(issuedCouponId)
            ?.takeIf { it.isOwnedBy(memberId) } // 남의 쿠폰은 "없는 쿠폰"으로 응답한다 (존재 여부를 알려주지 않는다)
            ?: throw ServiceException(ErrorCode.ISSUED_COUPON_NOT_FOUND)
        val couponEvent = couponEventRepository.findByIdOrNull(coupon.couponEventId)
            ?: throw ServiceException(ErrorCode.COUPON_EVENT_NOT_FOUND)

        val discountAmount = coupon.use(orderAmount, couponEvent.discount, LocalDateTime.now(clock))
        try {
            issuedCouponRepository.saveAndFlush(coupon)
        } catch (e: ObjectOptimisticLockingFailureException) {
            throw ServiceException(ErrorCode.ALREADY_USED_COUPON)
        }

        return CouponUsage(
            issuedCouponId = coupon.id,
            orderAmount = orderAmount,
            discountAmount = discountAmount,
        )
    }
}

data class OwnedCoupon(
    val coupon: IssuedCoupon,
    val couponEvent: CouponEvent,
)

data class CouponUsage(
    val issuedCouponId: Long,
    val orderAmount: Int,
    val discountAmount: Int,
) {
    val paymentAmount: Int
        get() = orderAmount - discountAmount
}
