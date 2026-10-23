package com.site.independent.error

import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.data.mapping.PropertyReferenceException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ServiceException::class)
    fun handleServiceException(e: ServiceException): ResponseEntity<ErrorResponse> = e.errorCode.toResponseEntity()

    // @Valid 검증 실패 (예: 이메일 형식 오류, 길이 제한 위반)
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val errors = e.bindingResult.fieldErrors.map {
            ErrorResponse.FieldErrorDetail(field = it.field, reason = it.defaultMessage.orEmpty())
        }
        return ErrorCode.INVALID_INPUT_VALUE.toResponseEntity(errors)
    }

    // JSON 파싱 실패 (예: non-null 필드 누락 시 jackson-module-kotlin이 여기서 막는다)
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        ErrorCode.INVALID_INPUT_VALUE.toResponseEntity()

    // 경로/쿼리 파라미터 타입 불일치 (예: /coupon-events/abc)
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        ErrorCode.INVALID_INPUT_VALUE.toResponseEntity()

    // 페이징 정렬 기준에 없는 필드를 넣은 경우 (예: ?sort=unknown)
    @ExceptionHandler(PropertyReferenceException::class)
    fun handlePropertyReference(e: PropertyReferenceException): ResponseEntity<ErrorResponse> = ErrorCode.INVALID_INPUT_VALUE.toResponseEntity()

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(e: NoResourceFoundException): ResponseEntity<ErrorResponse> = ErrorCode.RESOURCE_NOT_FOUND.toResponseEntity()

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(e: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> =
        ErrorCode.METHOD_NOT_ALLOWED.toResponseEntity()

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception", e)
        return ErrorCode.INTERNAL_SERVER_ERROR.toResponseEntity()
    }

    private fun ErrorCode.toResponseEntity(errors: List<ErrorResponse.FieldErrorDetail> = emptyList()): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(httpStatus).body(ErrorResponse.of(this, errors))
}
