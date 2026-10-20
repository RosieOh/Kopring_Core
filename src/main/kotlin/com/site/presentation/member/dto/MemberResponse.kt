package com.site.presentation.member.dto

import com.site.domain.member.entity.Member
import com.site.domain.member.entity.MemberRole
import java.time.LocalDateTime

data class MemberResponse(
    val id: Long,
    val email: String,
    val nickname: String,
    val role: MemberRole,
    val createdAt: LocalDateTime,
)

// Entity → DTO 변환을 확장 함수로. domain 계층은 presentation 계층을 모른 채로 남는다.
fun Member.toResponse(): MemberResponse =
    MemberResponse(
        id = id,
        email = email,
        nickname = nickname,
        role = role,
        createdAt = createdAt,
    )
