package com.site.independent.config

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

class ProdEnvironmentValidatorTest : FunSpec({

    val validator = ProdEnvironmentValidator()
    val application = SpringApplication()

    fun environment(profile: String, vararg variables: Pair<String, String>) =
        MockEnvironment().apply {
            setActiveProfiles(profile)
            variables.forEach { (name, value) -> setProperty(name, value) }
        }

    test("운영 프로필에서 필수 환경변수가 빠지면 빠진 이름을 알려주며 실패한다") {
        val environment = environment("prod", "DB_URL" to "jdbc:mysql://db/kopring", "DB_USERNAME" to "app")

        shouldThrow<IllegalStateException> { validator.postProcessEnvironment(environment, application) }.message shouldBe
            "운영 프로필에 필요한 환경변수가 없습니다: DB_PASSWORD, REDIS_HOST, JWT_SECRET (application-prod.properties 참고)"
    }

    test("운영 프로필에서 필수 환경변수가 모두 있으면 통과한다") {
        val variables = ProdEnvironmentValidator.REQUIRED_VARIABLES.map { it to "value" }.toTypedArray()

        shouldNotThrowAny { validator.postProcessEnvironment(environment("prod", *variables), application) }
    }

    test("운영 프로필이 아니면 검사하지 않는다") {
        shouldNotThrowAny { validator.postProcessEnvironment(environment("local"), application) }
    }
})
