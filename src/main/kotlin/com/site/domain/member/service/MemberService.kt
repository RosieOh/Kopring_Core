package com.site.domain.member.service

import com.site.domain.member.entity.Member
import com.site.domain.member.repository.MemberRepository
import com.site.independent.error.exception.ServiceException
import com.site.independent.type.ErrorCode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class MemberService(
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    @Transactional
    fun signUp(
        email: String,
        rawPassword: String,
        nickname: String,
        phoneNumber: String? = null,
    ): Member {
        if (memberRepository.existsByEmail(email)) {
            throw ServiceException(ErrorCode.DUPLICATE_EMAIL)
        }

        val member = Member(
            email = email,
            password = passwordEncoder.encode(rawPassword),
            nickname = nickname,
            phoneNumber = phoneNumber,
        )

        // 같은 이메일 가입이 existsByEmail 과 저장 사이에 끼어들면 DB unique 제약이 최후의 방어선이 된다.
        // (길이 같은 다른 제약 위반은 요청 검증에서 먼저 걸러지므로, 여기서 나는 무결성 위반은 이메일 중복뿐이다)
        return try {
            memberRepository.saveAndFlush(member)
        } catch (e: DataIntegrityViolationException) {
            throw ServiceException(ErrorCode.DUPLICATE_EMAIL)
        }
    }

    fun getMember(memberId: Long): Member =
        memberRepository.findByIdOrNull(memberId)
            ?: throw ServiceException(ErrorCode.USER_NOT_FOUND)
}
