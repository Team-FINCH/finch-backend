package com.finch.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 공개 백엔드 계약(apiSpec)을 런타임 OpenAPI 문서로 제공한다. */
@Configuration
public class OpenApiConfig {

	public static final String BEARER_AUTH = "bearerAuth";
	public static final String REFRESH_COOKIE = "refreshCookie";

	@Bean
	OpenAPI finchOpenApi() {
		return new OpenAPI()
			.info(new Info()
				.title("Finch API")
				.version("v1")
				.description("Finch 모의투자 서비스 공개 API. 기준 문서: docs/api/apiSpec.md")
				.contact(new Contact().name("Finch Team"))
				.license(new License().name("Private project")))
			.servers(List.of(
				new Server().url("https://www.finchapp.org").description("운영"),
				new Server().url("http://localhost:8080").description("로컬")))
			.components(new Components()
				.addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
					.type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.bearerFormat("JWT")
					.description("카카오 로그인 또는 토큰 재발급 응답의 Access Token"))
				.addSecuritySchemes(REFRESH_COOKIE, new SecurityScheme()
					.type(SecurityScheme.Type.APIKEY)
					.in(SecurityScheme.In.COOKIE)
					.name("refreshToken")
					.description("HttpOnly Refresh Token 쿠키")))
			.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
	}
}
