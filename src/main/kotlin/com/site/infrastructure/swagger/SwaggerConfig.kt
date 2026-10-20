package com.site.infrastructure.swagger

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityScheme
import org.springframework.context.annotation.Configuration

/** http://localhost:8080/swagger-ui/index.html 우측 상단 Authorize 버튼에 accessToken 을 넣으면 된다. */
@Configuration
@OpenAPIDefinition(
    info = Info(title = "Kopring Core API", version = "v1"),
    security = [SecurityRequirement(name = "bearerAuth")],
)
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
)
class SwaggerConfig
