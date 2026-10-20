package com.site.domain.member.service

import com.site.domain.member.entity.Member
import com.site.domain.member.repository.MemberRepository
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder

class MemberServiceTest {

    private val memberRepository = mockk<MemberRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val memberService = MemberService(memberRepository, passwordEncoder)

    @Test
    fun `회원가입 시 비밀번호를 인코딩해서 저장한다`() {
        val saved = slot<Member>()
        every { memberRepository.existsByEmail("user@test.com") } returns false
        every { passwordEncoder.encode("password123") } returns "{bcrypt}encoded"
        every { memberRepository.saveAndFlush(capture(saved)) } answers { saved.captured }

        memberService.signUp(email = "user@test.com", rawPassword = "password123", nickname = "tester")

        assertEquals("{bcrypt}encoded", saved.captured.password)
        assertEquals("user@test.com", saved.captured.email)
    }

    @Test
    fun `이미 가입된 이메일이면 DUPLICATE_EMAIL 예외가 발생하고 저장하지 않는다`() {
        every { memberRepository.existsByEmail("user@test.com") } returns true

        val exception = assertThrows<ServiceException> {
            memberService.signUp(email = "user@test.com", rawPassword = "password123", nickname = "tester")
        }

        assertEquals(ErrorCode.DUPLICATE_EMAIL, exception.errorCode)
        verify(exactly = 0) { memberRepository.saveAndFlush(any()) }
    }

    @Test
    fun `중복 검사를 통과했지만 동시 가입으로 unique 제약에 걸리면 DUPLICATE_EMAIL`() {
        every { memberRepository.existsByEmail("user@test.com") } returns false
        every { passwordEncoder.encode(any()) } returns "{bcrypt}encoded"
        every { memberRepository.saveAndFlush(any()) } throws DataIntegrityViolationException("Duplicate entry")

        val exception = assertThrows<ServiceException> {
            memberService.signUp(email = "user@test.com", rawPassword = "password123", nickname = "tester")
        }

        assertEquals(ErrorCode.DUPLICATE_EMAIL, exception.errorCode)
    }
}
