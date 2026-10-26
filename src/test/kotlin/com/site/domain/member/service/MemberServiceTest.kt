package com.site.domain.member.service

import com.site.domain.member.entity.Member
import com.site.domain.member.repository.MemberRepository
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder

/** MockK 로 리포지토리를 대신한다. 스펙은 테스트(leaf)마다 새로 만들어지므로 목도 매번 새것이다. (ProjectConfig) */
class MemberServiceTest : BehaviorSpec({

    val memberRepository = mockk<MemberRepository>()
    val passwordEncoder = mockk<PasswordEncoder>()
    val memberService = MemberService(memberRepository, passwordEncoder)

    every { passwordEncoder.encode("password123") } returns "{bcrypt}encoded"

    fun signUp() = memberService.signUp(email = "user@test.com", rawPassword = "password123", nickname = "tester")

    Given("처음 쓰는 이메일") {
        every { memberRepository.existsByEmail("user@test.com") } returns false

        When("가입하면") {
            val saved = slot<Member>()
            every { memberRepository.saveAndFlush(capture(saved)) } answers { saved.captured }

            signUp()

            Then("비밀번호를 인코딩해서 저장한다") {
                saved.captured.password shouldBe "{bcrypt}encoded"
                saved.captured.email shouldBe "user@test.com"
            }
        }

        When("중복 검사는 통과했지만 동시에 같은 이메일로 가입한 요청이 먼저 저장되면") {
            every { memberRepository.saveAndFlush(any()) } throws DataIntegrityViolationException("Duplicate entry")

            Then("unique 제약 위반을 DUPLICATE_EMAIL 로 바꿔 알려준다") {
                shouldThrow<ServiceException> { signUp() }.errorCode shouldBe ErrorCode.DUPLICATE_EMAIL
            }
        }
    }

    Given("이미 가입된 이메일") {
        every { memberRepository.existsByEmail("user@test.com") } returns true

        Then("DUPLICATE_EMAIL 이고 저장하지 않는다") {
            shouldThrow<ServiceException> { signUp() }.errorCode shouldBe ErrorCode.DUPLICATE_EMAIL
            verify(exactly = 0) { memberRepository.saveAndFlush(any()) }
        }
    }
})
