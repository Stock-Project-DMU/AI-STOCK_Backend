package com.teamfp.aistock.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * 로직 검증용 Swagger UI 설정. 보호된 API를 브라우저에서 바로 눌러볼 수 있도록
 * Authorize 버튼에 JWT를 입력하면 모든 요청에 Authorization: Bearer {token} 헤더가
 * 자동으로 붙게 하는 Bearer 인증 스킴만 등록한다(운영 배포 전 제거 예정인 임시 개발 도구).
 */
@Configuration
public class SwaggerConfig {

    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI STOCK API")
                        .description("모의투자 + AI 재무설계 백엔드 API — 로직 검증용")
                        .version("v0.0.1"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
                .schemaRequirement(BEARER_SCHEME_NAME, new SecurityScheme()
                        .name(BEARER_SCHEME_NAME)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"));
    }
}
