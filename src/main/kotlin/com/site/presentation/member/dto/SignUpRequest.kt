package com.site.presentation.member.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Kotlin 에서 Bean Validation 을 쓸 때는 반드시 @field: 를 붙인다.
 * 안 붙이면 애너테이션이 생성자 파라미터에 붙어서 검증이 조용히 무시된다.
 */
data class SignUpRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 100)
    val email: String,

    @field:Size(min = 8, max = 20)
    val password: String,

    @field:NotBlank
    @field:Size(max = 20)
    val nickname: String,
)
