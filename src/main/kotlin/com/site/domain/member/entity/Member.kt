package com.site.domain.member.entity

import com.site.domain.common.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * - data class 로 만들지 않는다: equals/hashCode/toString 자동 생성이 지연 로딩·연관관계와 충돌한다.
 * - 생성자 파라미터는 val/var 없이 받고, 프로퍼티는 protected set 으로 외부 변경을 막는다.
 * - 기본 생성자는 kotlin("plugin.jpa"), 프록시용 open 은 allOpen 설정이 만들어준다.
 */
@Entity
@Table(name = "member")
class Member(
    email: String,
    password: String,
    nickname: String,
    role: MemberRole = MemberRole.USER,
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Column(nullable = false, unique = true, length = 100)
    var email: String = email
        protected set

    @Column(nullable = false)
    var password: String = password
        protected set

    @Column(nullable = false, length = 20)
    var nickname: String = nickname
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: MemberRole = role
        protected set
}
