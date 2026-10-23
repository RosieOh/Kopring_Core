package com.site.independent.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

class ProdEnvironmentValidatorTest {

    private val validator = ProdEnvironmentValidator()
    private val application = SpringApplication()

    private fun environment(profile: String, vararg variables: Pair<String, String>) =
        MockEnvironment().apply {
            setActiveProfiles(profile)
            variables.forEach { (name, value) -> setProperty(name, value) }
        }

    @Test
    fun `운영 프로필에서 필수 환경변수가 빠지면 빠진 이름을 알려주며 실패한다`() {
        val environment = environment("prod", "DB_URL" to "jdbc:mysql://db/kopring", "DB_USERNAME" to "app")

        val exception = assertThrows<IllegalStateException> { validator.postProcessEnvironment(environment, application) }

        assertEquals(
            "운영 프로필에 필요한 환경변수가 없습니다: DB_PASSWORD, REDIS_HOST, JWT_SECRET (application-prod.properties 참고)",
            exception.message,
        )
    }

    @Test
    fun `운영 프로필에서 필수 환경변수가 모두 있으면 통과한다`() {
        val variables = ProdEnvironmentValidator.REQUIRED_VARIABLES.map { it to "value" }.toTypedArray()

        assertDoesNotThrow { validator.postProcessEnvironment(environment("prod", *variables), application) }
    }

    @Test
    fun `운영 프로필이 아니면 검사하지 않는다`() {
        assertDoesNotThrow { validator.postProcessEnvironment(environment("local"), application) }
    }
}
