package com.site.independent.type

enum class SuccessCode(
    val httpStatus: Int,
    val code: String,
    val message: String,
) {
    ENTERED_SUCCESS(200, "ENTERED_SUCCESS", "입장 완료에 성공하였습니다."),
    REMOTE_WAITING_SUCCESS(201, "REMOTE_WAITING_SUCCESS", "원격 웨이팅에 성공하였습니다."),
    ONSITE_WAITING_SUCCESS(201, "ONSITE_WAITING_SUCCESS", "현장 웨이팅에 성공하였습니다."),
    USER_CALL_SUCCESS(201, "USER_CALL_SUCCESS", "사용자 호출에 성공하였습니다."),
    WAITING_CANCEL_SUCCESS(201, "WAITING_CANCEL_SUCCESS", "웨이팅 취소에 성공하였습니다."),
    WAITING_FORCE_CANCEL_SUCCESS(201, "WAITING_FORCE_CANCEL_SUCCESS", "웨이팅 강제 취소에 성공하였습니다."),
    MANAGE_PUB_SUCCESS(201, "MANAGE_PUB_SUCCESS", "주점 정보 수정에 성공하였습니다."),
    EXIT_USER_SUCCESS(201, "EXIT_USER_SUCCESS", "퇴장 처리에 성공하였습니다."),
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
    ISSUE_COUPON_SUCCESS(201, "ISSUE_COUPON_SUCCESS", "쿠폰 발급에 성공하였습니다.");
}