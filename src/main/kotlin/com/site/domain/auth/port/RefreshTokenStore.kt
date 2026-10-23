package com.site.domain.auth.port

/**
 * Refresh Token 저장소 포트.
 */
interface RefreshTokenStore {
    fun save(refreshToken: String, memberId: Long)

    fun findMemberId(refreshToken: String): Long?

    /** 조회와 삭제를 원자적으로 한다. 같은 토큰은 딱 한 번만 꺼낼 수 있다. */
    fun consume(refreshToken: String): Long?

    fun delete(refreshToken: String)
}
