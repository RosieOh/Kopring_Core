package com.site.infrastructure.biztalk

/**
 * 카카오 알림톡 발송기. 실제 구현(Solapi)과 로그 대체 구현을 설정으로 바꿔 끼운다.
 *
 * 발송 실패를 예외 대신 반환값(AlimtalkSendResult)으로 돌려준다.
 * 호출하는 쪽이 when 으로 성공/실패를 빠짐없이 처리하도록 컴파일러가 강제해준다.
 */
interface AlimtalkSender {
    fun send(message: AlimtalkMessage): AlimtalkSendResult
}

/**
 * @property templateId 카카오에 사전 승인받은 템플릿 ID. 알림톡은 승인된 템플릿과 문구가 정확히 일치해야 발송된다.
 * @property variables 템플릿 변수 치환값. 예) "#{닉네임}" to "로지"
 */
data class AlimtalkMessage(
    val to: String,
    val templateId: String,
    val variables: Map<String, String>,
)

/**
 * sealed: 가능한 결과가 이 파일 안에 정의된 것뿐이라고 컴파일러에 알려준다. when 에서 else 가 필요 없다.
 * data object: 상태가 없는 결과는 싱글톤 객체로 충분하다. (toString 도 "Success" 로 깔끔하게 나온다)
 */
sealed interface AlimtalkSendResult {
    data object Success : AlimtalkSendResult

    /** @property retryable 일시적인 오류(네트워크 등)라 다시 보내면 성공할 수 있는지 */
    data class Failure(
        val reason: String,
        val retryable: Boolean,
    ) : AlimtalkSendResult
}

/** 로그에 휴대폰 번호를 그대로 남기지 않는다. 01012345678 → 010****5678 */
fun String.maskPhoneNumber(): String = if (length < 8) "****" else take(3) + "****" + takeLast(4)
