package com.site.presentation.auth.dto

import com.site.domain.auth.service.TokenPair

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
)

fun TokenPair.toResponse(): TokenResponse =
    TokenResponse(
        accessToken = accessToken,
        refreshToken = refreshToken,
    )
