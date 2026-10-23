package com.site.independent.type

enum class SuccessCode(
    val httpStatus: Int,
    val code: String,
    val message: String,
) {
    SIGN_UP_SUCCESS(201, "SIGN_UP_SUCCESS", "회원가입에 성공하였습니다."),
    LOGIN_SUCCESS(200, "LOGIN_SUCCESS", "로그인에 성공하였습니다."),
    REISSUE_SUCCESS(200, "REISSUE_SUCCESS", "토큰 재발급에 성공하였습니다."),
    LOGOUT_SUCCESS(200, "LOGOUT_SUCCESS", "로그아웃에 성공하였습니다."),
    GET_MY_INFO_SUCCESS(200, "GET_MY_INFO_SUCCESS", "내 정보 조회에 성공하였습니다."),
    CREATE_COUPON_EVENT_SUCCESS(201, "CREATE_COUPON_EVENT_SUCCESS", "쿠폰 이벤트 등록에 성공하였습니다."),
    GET_COUPON_EVENTS_SUCCESS(200, "GET_COUPON_EVENTS_SUCCESS", "쿠폰 이벤트 목록 조회에 성공하였습니다."),
    GET_COUPON_EVENT_SUCCESS(200, "GET_COUPON_EVENT_SUCCESS", "쿠폰 이벤트 조회에 성공하였습니다."),
    UPDATE_COUPON_EVENT_SUCCESS(200, "UPDATE_COUPON_EVENT_SUCCESS", "쿠폰 이벤트 수정에 성공하였습니다."),
    DELETE_COUPON_EVENT_SUCCESS(200, "DELETE_COUPON_EVENT_SUCCESS", "쿠폰 이벤트 삭제에 성공하였습니다."),
    ISSUE_COUPON_SUCCESS(201, "ISSUE_COUPON_SUCCESS", "쿠폰 발급에 성공하였습니다."),
    ENTER_COUPON_QUEUE_SUCCESS(202, "ENTER_COUPON_QUEUE_SUCCESS", "쿠폰 발급 대기열에 등록되었습니다."),
    GET_COUPON_QUEUE_STATUS_SUCCESS(200, "GET_COUPON_QUEUE_STATUS_SUCCESS", "대기열 상태 조회에 성공하였습니다."),
    GET_MY_COUPONS_SUCCESS(200, "GET_MY_COUPONS_SUCCESS", "내 쿠폰 목록 조회에 성공하였습니다."),
    USE_COUPON_SUCCESS(200, "USE_COUPON_SUCCESS", "쿠폰 사용에 성공하였습니다."),
}
