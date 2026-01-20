package com.site.independent.type

enum class ErrorCode(
    val httpStatus: Int,
    val message: String,
    val code: String,

) {
    INVALID_TOKEN(401, "INVALID_TOKEN", "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(401, "EXPIRED_TOKEN", "만료된 토큰입니다."),
    MALFORMED_TOKEN(401, "MALFORMED_TOKEN", "위/변조된 토큰입니다."),
    UNSUPPORTED_TOKEN(401, "UNSUPPORTED_TOKEN", "지원하지 않는 토큰입니다."),
    EMPTY_AUTHENTICATION(401, "EMPTY_AUTHENTICATION", "인증정보가 존재하지 않습니다."),
    ROLE_FORBIDDEN(403, "ROLE_FORBIDDEN", "액세스할 수 있는 권한이 아닙니다."),
    PASSWORD_NOT_MATCH(403, "PASSWORD_NOT_MATCH", "비밀번호가 일치하지 않습니다."),
    USER_NOT_FOUND(404, "USER_NOT_FOUND", "존재하지 않는 사용자입니다."),
    ADMIN_NOT_FOUND(404, "ADMIN_NOT_FOUND", "존재하지 않는 관리자입니다."),
    INTERNAL_SERVER_ERROR(500, "INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."),
    BIZTALK_SEND_FAIL(503, "BIZTALK_SEND_FAIL", "카카오 알림톡 전송에 실패했습니다."),
}