package com.site.independent.config

/**
 * 비동기 실행기 빈 이름. @Async("...") 에 쓰는 이름을 계층 공통 위치에 둬서,
 * domain 이 실행기를 만드는 infrastructure 설정 클래스를 import 하지 않아도 되게 한다.
 */
object AsyncExecutors {
    const val NOTIFICATION = "notificationExecutor"
}
