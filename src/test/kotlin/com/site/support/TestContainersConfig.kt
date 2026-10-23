package com.site.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 테스트용 MySQL / Redis 컨테이너를 스프링 빈으로 띄운다.
 * @ServiceConnection 이 컨테이너의 접속 정보를 DataSource / Redis 설정으로 등록해주므로 properties 에 적을 필요가 없다.
 * (Redisson 도 이 접속 정보를 그대로 읽는다)
 *
 * H2 대신 운영과 같은 MySQL 을 쓰는 이유: 락·격리 수준 동작과 DDL(컬럼 타입)이 MySQL 과 달라서
 * 동시성 테스트나 스키마 문제를 H2 로는 제대로 검증할 수 없다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestContainersConfig {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer<*> = MySQLContainer(DockerImageName.parse("mysql:8.4"))

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
}
