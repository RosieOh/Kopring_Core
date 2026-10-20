package com.site.domain.member.entity

enum class MemberRole {
    USER,
    ADMIN,
    ;

    val authority: String
        get() = "ROLE_$name"
}
