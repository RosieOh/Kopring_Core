package com.site.domain.member.repository

import com.site.domain.member.entity.Member
import org.springframework.data.jpa.repository.JpaRepository

interface MemberRepository : JpaRepository<Member, Long> {

    // Optional<Member> 대신 nullable 타입으로 받는다
    fun findByEmail(email: String): Member?

    fun existsByEmail(email: String): Boolean
}
