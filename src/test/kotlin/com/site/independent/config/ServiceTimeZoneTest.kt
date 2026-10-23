package com.site.independent.config

import com.site.domain.member.entity.Member
import com.site.domain.member.repository.MemberRepository
import com.site.support.IntegrationTest
import com.site.support.serviceNow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

/**
 * 테스트 JVM 은 UTC 로 돈다. (build.gradle.kts 의 -Duser.timezone=UTC)
 * 서버 OS 시간대에 기대는 코드가 있으면 서비스 시간대(Asia/Seoul)와 9시간 차이가 나서 여기서 걸린다.
 */
@IntegrationTest
class ServiceTimeZoneTest @Autowired constructor(
    private val clock: Clock,
    private val memberRepository: MemberRepository,
) {

    @AfterEach
    fun tearDown() {
        memberRepository.deleteAllInBatch()
    }

    @Test
    fun `테스트는 UTC 서버와 같은 조건에서 돈다`() {
        assertEquals(ZoneId.of("UTC"), ZoneId.systemDefault())
    }

    @Test
    fun `서비스 Clock 은 서버 시간대와 무관하게 Asia-Seoul 이다`() {
        assertEquals(ZoneId.of("Asia/Seoul"), clock.zone)
    }

    @Test
    fun `createdAt 같은 감사 시각도 서비스 시간대로 기록된다`() {
        val member = memberRepository.save(Member(email = "tz@test.com", password = "encoded", nickname = "tz"))

        val gap = Duration.between(member.createdAt, serviceNow()).abs()
        assertTrue(gap < Duration.ofMinutes(1)) { "createdAt=${member.createdAt}, 서비스 시각=${serviceNow()}" }
    }
}
