package com.site.infrastructure.security.jwt

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * application-*.properties 의 jwt.* 값이 생성자로 바인딩된다. (불변 객체)
 * 예) jwt.access-token-expiration=30m  →  Duration.ofMinutes(30)
 */
@ConfigurationProperties(prefix = "jwt")
data class JwtProperties(
    val secret: String,
    val accessTokenExpiration: Duration,
    val refreshTokenExpiration: Duration,
)
