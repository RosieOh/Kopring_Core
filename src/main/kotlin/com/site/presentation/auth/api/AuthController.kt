package com.site.presentation.auth.api

import com.site.domain.auth.service.AuthService
import com.site.independent.response.SuccessResponse
import com.site.independent.response.toResponseEntity
import com.site.independent.type.SuccessCode
import com.site.infrastructure.security.AuthMember
import com.site.presentation.auth.dto.LoginRequest
import com.site.presentation.auth.dto.RefreshTokenRequest
import com.site.presentation.auth.dto.TokenResponse
import com.site.presentation.auth.dto.toResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<SuccessResponse<TokenResponse>> {
        val tokens = authService.login(request.email, request.password)
        return SuccessCode.LOGIN_SUCCESS.toResponseEntity(tokens.toResponse())
    }

    @PostMapping("/reissue")
    fun reissue(@Valid @RequestBody request: RefreshTokenRequest): ResponseEntity<SuccessResponse<TokenResponse>> {
        val tokens = authService.reissue(request.refreshToken)
        return SuccessCode.REISSUE_SUCCESS.toResponseEntity(tokens.toResponse())
    }

    @PostMapping("/logout")
    fun logout(
        @AuthenticationPrincipal authMember: AuthMember,
        @Valid @RequestBody request: RefreshTokenRequest,
    ): ResponseEntity<SuccessResponse<Nothing?>> {
        authService.logout(
            memberId = authMember.memberId,
            accessTokenId = authMember.tokenId,
            accessTokenExpiresAt = authMember.tokenExpiresAt,
            refreshToken = request.refreshToken,
        )
        return SuccessCode.LOGOUT_SUCCESS.toResponseEntity()
    }
}
