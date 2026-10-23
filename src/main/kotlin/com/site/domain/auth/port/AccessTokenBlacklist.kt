package com.site.domain.auth.port

import java.time.Instant

/**
 * 로그아웃한 Access Token 목록 포트. 토큰이 원래 만료될 때까지만 기억하면 된다.
 */
interface AccessTokenBlacklist {
    fun add(tokenId: String, expiresAt: Instant)

    fun contains(tokenId: String): Boolean
}
