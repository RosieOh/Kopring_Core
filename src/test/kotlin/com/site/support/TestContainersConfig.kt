package com.site.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트용 Redis 컨테이너를 스프링 빈으로 띄운다.
 * @ServiceConnection 이 컨테이너의 호스트/포트를 Redis 접속 정보로 등록해주므로 properties 에 포트를 적을 필요가 없다.
 * (Redisson 도 이 접속 정보를 그대로 읽는다)
 */
@TestConfiguration(proxyBeanMethods = false)
class TestContainersConfig {

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
}
