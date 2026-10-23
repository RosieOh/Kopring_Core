package com.site.domain.auth.port

/**
 * Refresh Token 저장소 포트.
 *
 * 로그인 한 번에서 이어지는 refresh token 들은 같은 패밀리(familyId)로 묶인다.
 * 이미 쓴 토큰이 다시 오면 탈취 신호로 보고 패밀리 전체를 폐기한다.
 */
interface RefreshTokenStore {
    fun save(refreshToken: String, owner: RefreshTokenOwner)

    fun findOwner(refreshToken: String): RefreshTokenOwner?

    /**
     * 토큰을 꺼내면서 "사용됨"으로 표시한다. 꺼내기와 표시는 원자적이어야 한다.
     * (따로 하면 동시에 들어온 두 요청이 둘 다 "모르는 토큰"으로 판단돼 재사용을 놓칠 수 있다)
     */
    fun consume(refreshToken: String): ConsumeResult

    fun revokeFamily(familyId: String)

    fun isFamilyRevoked(familyId: String): Boolean
}

data class RefreshTokenOwner(
    val memberId: Long,
    val familyId: String,
)

sealed interface ConsumeResult {
    /** 정상 사용 — 이 토큰은 이제 "사용됨" 상태가 됐다 */
    data class Consumed(
        val owner: RefreshTokenOwner,
    ) : ConsumeResult

    /** 이미 사용된 토큰이 다시 왔다 — 탈취 의심 */
    data class Reused(
        val owner: RefreshTokenOwner,
    ) : ConsumeResult

    /** 발급한 적 없거나 만료된 토큰 */
    data object Invalid : ConsumeResult
}
