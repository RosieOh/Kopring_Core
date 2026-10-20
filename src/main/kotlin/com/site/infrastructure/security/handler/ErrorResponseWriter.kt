package com.site.infrastructure.security.handler

import com.fasterxml.jackson.databind.ObjectMapper
import com.site.independent.error.ErrorResponse
import com.site.independent.type.ErrorCode
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType

/**
 * 필터 단계의 예외는 @RestControllerAdvice 까지 오지 않으므로 응답을 직접 써야 한다.
 * HttpServletResponse 에 확장 함수로 붙여서 response.writeErrorResponse(...) 처럼 호출한다.
 */
fun HttpServletResponse.writeErrorResponse(objectMapper: ObjectMapper, errorCode: ErrorCode) {
    status = errorCode.httpStatus
    contentType = MediaType.APPLICATION_JSON_VALUE
    characterEncoding = Charsets.UTF_8.name()
    objectMapper.writeValue(writer, ErrorResponse.of(errorCode))
}
