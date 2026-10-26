package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.IsolationMode

/**
 * Kotest 전역 설정. 이 패키지·이름(io.kotest.provided.ProjectConfig)이면 Kotest 가 자동으로 찾는다.
 *
 * 기본값(SingleInstance)은 스펙 인스턴스 하나를 모든 테스트가 공유해서, Given 블록에서 만든 객체를
 * 한 Then 이 바꾸면 다음 Then 이 바뀐 객체를 보게 된다. 테스트(leaf)마다 새 인스턴스로 실행해 서로 섞이지 않게 한다.
 */
object ProjectConfig : AbstractProjectConfig() {
    override val isolationMode = IsolationMode.InstancePerLeaf
}
