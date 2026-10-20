package com.site.infrastructure.database

import com.site.domain.member.entity.Member
import com.site.domain.member.entity.MemberRole
import com.site.domain.member.repository.MemberRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * local 프로필에서만 기동 시 관리자 계정을 만들어 둔다. (이미 있으면 건너뜀)
 * 운영에서는 코드로 관리자를 만들지 않는다. DB 에서 role 을 직접 바꾸거나 관리자 초대 기능을 따로 둔다.
 *
 * Kotlin 문자열에서 $ 는 템플릿 문법이라 @Value 의 ${...} 는 \$ 로 이스케이프해야 한다.
 */
@Component
@Profile("local")
class LocalAdminInitializer(
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${admin.init.email}") private val email: String,
    @Value("\${admin.init.password}") private val password: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (memberRepository.existsByEmail(email)) return

        memberRepository.save(
            Member(
                email = email,
                password = passwordEncoder.encode(password),
                nickname = "관리자",
                role = MemberRole.ADMIN,
            ),
        )
        log.info("로컬 관리자 계정을 생성했습니다: {}", email)
    }
}
