package com.site.independent.util

/** 로그에 휴대폰 번호를 그대로 남기지 않는다. 01012345678 → 010****5678 */
fun String.maskPhoneNumber(): String = if (length < 8) "****" else take(3) + "****" + takeLast(4)
