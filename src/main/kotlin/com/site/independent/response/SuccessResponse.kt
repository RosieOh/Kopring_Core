package com.site.independent.response

import com.site.independent.type.SuccessCode
import org.springframework.http.ResponseEntity

data class SuccessResponse<T>(
    val httpStatus: Int,
    val code: String,
    val message: String,
    val data: T,
) {
    companion object {
        fun <T> of(successCode: SuccessCode, data: T): SuccessResponse<T> =
            SuccessResponse(
                httpStatus = successCode.httpStatus,
                code = successCode.code,
                message = successCode.message,
                data = data,
            )
    }
}

fun <T> SuccessCode.toResponseEntity(data: T): ResponseEntity<SuccessResponse<T>> =
    ResponseEntity.status(httpStatus).body(SuccessResponse.of(this, data))

// 돌려줄 데이터가 없는 응답 ("data": null). Nothing? 는 null 하나만 담을 수 있는 타입이다.
fun SuccessCode.toResponseEntity(): ResponseEntity<SuccessResponse<Nothing?>> =
    toResponseEntity(null)
