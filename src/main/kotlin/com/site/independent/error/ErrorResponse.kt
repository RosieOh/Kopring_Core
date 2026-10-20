package com.site.independent.error

import com.site.independent.type.ErrorCode

data class ErrorResponse(
    val httpStatus: Int,
    val code: String,
    val message: String,
    val errors: List<FieldErrorDetail> = emptyList(),
) {
    data class FieldErrorDetail(
        val field: String,
        val reason: String,
    )

    companion object {
        fun of(errorCode: ErrorCode, errors: List<FieldErrorDetail> = emptyList()): ErrorResponse =
            ErrorResponse(
                httpStatus = errorCode.httpStatus,
                code = errorCode.code,
                message = errorCode.message,
                errors = errors,
            )
    }
}
