package com.site.domain.auth.service

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
)
