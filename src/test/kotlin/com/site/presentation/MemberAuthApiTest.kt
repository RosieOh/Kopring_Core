package com.site.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import com.site.domain.member.entity.Member
import com.site.domain.member.entity.MemberRole
import com.site.domain.member.repository.MemberRepository
import com.site.infrastructure.security.jwt.JwtProperties
import com.site.support.IntegrationTest
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.Date

@IntegrationTest
class MemberAuthApiTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtProperties: JwtProperties,
) {

    @AfterEach
    fun tearDown() {
        memberRepository.deleteAllInBatch()
    }

    @Test
    fun `회원가입 - 로그인 - 내 정보 조회`() {
        signUp(email = "user@test.com", password = "password123", nickname = "tester")
            .andExpect {
                status { isCreated() }
                jsonPath("$.code") { value("SIGN_UP_SUCCESS") }
                jsonPath("$.data.email") { value("user@test.com") }
                jsonPath("$.data.role") { value("USER") }
                jsonPath("$.data.password") { doesNotExist() }
            }

        val tokens = loginAndGetTokens(email = "user@test.com", password = "password123")

        getMyInfo(tokens.accessToken)
            .andExpect {
                status { isOk() }
                jsonPath("$.data.nickname") { value("tester") }
            }
    }

    @Test
    fun `이메일 형식이 틀리면 400과 필드 에러를 돌려준다`() {
        signUp(email = "not-an-email", password = "password123", nickname = "tester")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_INPUT_VALUE") }
                jsonPath("$.errors[0].field") { value("email") }
            }
    }

    @Test
    fun `휴대폰 번호는 선택 입력이고, 입력하면 형식을 검사한다`() {
        signUp(email = "phone@test.com", password = "password123", nickname = "tester", phoneNumber = "01012345678")
            .andExpect {
                status { isCreated() }
                jsonPath("$.data.phoneNumber") { value("01012345678") }
            }

        signUp(email = "bad@test.com", password = "password123", nickname = "tester", phoneNumber = "010-1234-5678")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[0].field") { value("phoneNumber") }
            }
    }

    @Test
    fun `필수 필드가 JSON 에서 빠지면 400`() {
        mockMvc.post("/api/members") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email": "user@test.com", "password": "password123"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_INPUT_VALUE") }
        }
    }

    @Test
    fun `같은 이메일로 두 번 가입하면 409`() {
        signUp(email = "user@test.com", password = "password123", nickname = "tester")
        signUp(email = "user@test.com", password = "password123", nickname = "tester2")
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("DUPLICATE_EMAIL") }
            }
    }

    @Test
    fun `비밀번호가 틀리면 401 INVALID_CREDENTIALS`() {
        signUp(email = "user@test.com", password = "password123", nickname = "tester")

        login(email = "user@test.com", password = "wrong-password")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("INVALID_CREDENTIALS") }
            }
    }

    @Test
    fun `토큰 없이 내 정보를 조회하면 401 EMPTY_AUTHENTICATION`() {
        mockMvc.get("/api/members/me")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("EMPTY_AUTHENTICATION") }
            }
    }

    @Test
    fun `위조된 토큰으로 조회하면 401 MALFORMED_TOKEN`() {
        getMyInfo("not-a-jwt")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("MALFORMED_TOKEN") }
            }
    }

    @Test
    fun `서명은 맞는데 role 클레임이 없는 토큰이면 500 이 아니라 401 INVALID_TOKEN`() {
        val token = Jwts.builder()
            .subject("1")
            .id("token-id")
            .expiration(Date(System.currentTimeMillis() + 60_000))
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.secret)))
            .compact()

        getMyInfo(token)
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("INVALID_TOKEN") }
            }
    }

    @Test
    fun `refresh token 으로 재발급하면 새 토큰 쌍을 주고, 한 번 쓴 refresh token 은 재사용으로 거절한다`() {
        signUp(email = "user@test.com", password = "password123", nickname = "tester")
        val tokens = loginAndGetTokens(email = "user@test.com", password = "password123")

        val reissued = reissue(tokens.refreshToken)
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("REISSUE_SUCCESS") }
            }
            .andReturn().toTokens()

        assertNotEquals(tokens.refreshToken, reissued.refreshToken)
        getMyInfo(reissued.accessToken).andExpect { status { isOk() } }

        reissue(tokens.refreshToken)
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("REFRESH_TOKEN_REUSED") }
            }
    }

    @Test
    fun `이미 쓴 refresh token 이 다시 오면 같은 로그인에서 이어진 토큰이 모두 무효가 된다`() {
        signUp(email = "user@test.com", password = "password123", nickname = "tester")
        val stolen = loginAndGetTokens(email = "user@test.com", password = "password123")

        // 공격자가 훔친 토큰으로 먼저 재발급을 받는다
        val attackerTokens = reissue(stolen.refreshToken).andExpect { status { isOk() } }.andReturn().toTokens()

        // 정상 사용자가 자기가 가진(이미 쓰인) 토큰으로 재발급을 시도 → 재사용 감지
        reissue(stolen.refreshToken).andExpect { jsonPath("$.code") { value("REFRESH_TOKEN_REUSED") } }

        // 공격자가 받아 간 새 토큰도 함께 폐기된다
        reissue(attackerTokens.refreshToken)
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
            }
    }

    @Test
    fun `재사용이 감지돼도 다른 기기의 로그인은 유지된다`() {
        signUp(email = "user@test.com", password = "password123", nickname = "tester")
        val phone = loginAndGetTokens(email = "user@test.com", password = "password123")
        val laptop = loginAndGetTokens(email = "user@test.com", password = "password123")

        reissue(phone.refreshToken).andExpect { status { isOk() } }
        reissue(phone.refreshToken).andExpect { jsonPath("$.code") { value("REFRESH_TOKEN_REUSED") } }

        reissue(laptop.refreshToken).andExpect { status { isOk() } }
    }

    @Test
    fun `로그아웃하면 그 access token 과 refresh token 을 더 이상 쓸 수 없다`() {
        signUp(email = "user@test.com", password = "password123", nickname = "tester")
        val tokens = loginAndGetTokens(email = "user@test.com", password = "password123")

        logout(tokens)
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("LOGOUT_SUCCESS") }
            }

        getMyInfo(tokens.accessToken)
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("LOGGED_OUT_TOKEN") }
            }
        reissue(tokens.refreshToken)
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `다른 회원의 refresh token 을 넣고 로그아웃해도 그 토큰은 지워지지 않는다`() {
        signUp(email = "victim@test.com", password = "password123", nickname = "victim")
        signUp(email = "other@test.com", password = "password123", nickname = "other")
        val victim = loginAndGetTokens(email = "victim@test.com", password = "password123")
        val other = loginAndGetTokens(email = "other@test.com", password = "password123")

        logout(other.copy(refreshToken = victim.refreshToken))
            .andExpect { status { isOk() } }

        reissue(victim.refreshToken)
            .andExpect { status { isOk() } }
    }

    @Test
    fun `일반 회원이 관리자 API 에 접근하면 403 ROLE_FORBIDDEN`() {
        signUp(email = "user@test.com", password = "password123", nickname = "tester")
        val tokens = loginAndGetTokens(email = "user@test.com", password = "password123")

        mockMvc.get("/api/admin/anything") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.accessToken}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("ROLE_FORBIDDEN") }
        }
    }

    @Test
    fun `관리자는 관리자 API 에 접근할 수 있다`() {
        memberRepository.save(
            Member(
                email = "admin@test.com",
                password = passwordEncoder.encode("password123"),
                nickname = "admin",
                role = MemberRole.ADMIN,
            ),
        )
        val tokens = loginAndGetTokens(email = "admin@test.com", password = "password123")

        // 아직 관리자 API 가 없으므로 보안 필터를 통과해 404 가 나면 권한 검사는 통과한 것이다
        mockMvc.get("/api/admin/anything") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.accessToken}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
        }
    }

    private data class Tokens(
        val accessToken: String,
        val refreshToken: String,
    )

    private fun signUp(
        email: String,
        password: String,
        nickname: String,
        phoneNumber: String? = null,
    ) = mockMvc.post("/api/members") {
        contentType = MediaType.APPLICATION_JSON
        // buildMap: 조건에 따라 항목을 넣고 빼면서 읽기 전용 Map 을 만든다. (phoneNumber 가 없으면 키 자체를 뺀다)
        content = objectMapper.writeValueAsString(
            buildMap {
                put("email", email)
                put("password", password)
                put("nickname", nickname)
                phoneNumber?.let { put("phoneNumber", it) }
            },
        )
    }

    private fun login(email: String, password: String) =
        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))
        }

    private fun reissue(refreshToken: String) =
        mockMvc.post("/api/auth/reissue") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("refreshToken" to refreshToken))
        }

    private fun logout(tokens: Tokens) =
        mockMvc.post("/api/auth/logout") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.accessToken}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("refreshToken" to tokens.refreshToken))
        }

    private fun getMyInfo(accessToken: String) =
        mockMvc.get("/api/members/me") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        }

    private fun loginAndGetTokens(email: String, password: String): Tokens =
        login(email, password)
            .andExpect { status { isOk() } }
            .andReturn().toTokens()

    private fun MvcResult.toTokens(): Tokens {
        val data = objectMapper.readTree(response.contentAsString)["data"]
        return Tokens(
            accessToken = data["accessToken"].asText(),
            refreshToken = data["refreshToken"].asText(),
        )
    }
}
