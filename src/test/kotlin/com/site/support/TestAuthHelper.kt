package com.site.support

import com.fasterxml.jackson.databind.ObjectMapper
import com.site.domain.member.entity.Member
import com.site.domain.member.entity.MemberRole
import com.site.domain.member.repository.MemberRepository
import org.springframework.boot.test.context.TestComponent
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * 통합 테스트용: 회원을 만들고 로그인해서 access token 을 돌려준다.
 *
 * @TestComponent 는 컴포넌트 스캔에서 제외되고, @Import 한 테스트(@IntegrationTest)에서만 빈으로 등록된다.
 * (src/test 아래의 일반 @Component 는 com.site 패키지 스캔에 걸려 의도치 않게 등록될 수 있다)
 */
@TestComponent
class TestAuthHelper(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    fun createMemberAndLogin(email: String, role: MemberRole = MemberRole.USER): String {
        memberRepository.save(
            Member(email = email, password = passwordEncoder.encode(PASSWORD), nickname = "tester", role = role),
        )

        val response = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to PASSWORD))
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        return objectMapper.readTree(response)["data"]["accessToken"].asText()
    }

    companion object {
        private const val PASSWORD = "password123"
    }
}
