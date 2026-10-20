package com.site.presentation.member.api

import com.site.domain.member.service.MemberService
import com.site.independent.response.SuccessResponse
import com.site.independent.response.toResponseEntity
import com.site.independent.type.SuccessCode
import com.site.infrastructure.security.AuthMember
import com.site.presentation.member.dto.MemberResponse
import com.site.presentation.member.dto.SignUpRequest
import com.site.presentation.member.dto.toResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/members")
class MemberController(
    private val memberService: MemberService,
) {

    @PostMapping
    fun signUp(@Valid @RequestBody request: SignUpRequest): ResponseEntity<SuccessResponse<MemberResponse>> {
        val member = memberService.signUp(
            email = request.email,
            rawPassword = request.password,
            nickname = request.nickname,
        )
        return SuccessCode.SIGN_UP_SUCCESS.toResponseEntity(member.toResponse())
    }

    @GetMapping("/me")
    fun getMyInfo(@AuthenticationPrincipal authMember: AuthMember): ResponseEntity<SuccessResponse<MemberResponse>> {
        val member = memberService.getMember(authMember.memberId)
        return SuccessCode.GET_MY_INFO_SUCCESS.toResponseEntity(member.toResponse())
    }
}
