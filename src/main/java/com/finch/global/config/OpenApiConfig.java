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

/**
 * 공개 백엔드 계약(apiSpec)을 런타임 OpenAPI 문서로 제공한다.
 * <p>
 * <b>서버 주소는 상대 경로 {@code /} 하나다.</b> Swagger "Try it out" 이 <b>Swagger 를 연 그 주소</b>로 요청을 보낸다 — 운영
 * {@code https://finchapp.org} 로 열든, 백엔드 직결 호스트로 열든, 로컬 {@code http://localhost:8080} 이든 같은 문서로 동작한다.
 * 절대 주소를 적으면 도메인이 바뀔 때마다 여기가 낡고(실제로 {@code www.finchapp.org} 로 적혀 있었는데 그 주소는 DNS 가 없었다),
 * Swagger 를 연 호스트와 다르면 교차 출처 요청이 되어 CORS 에 막힌다.
 */
@Configuration
public class OpenApiConfig {

	public static final String BEARER_AUTH = "bearerAuth";
	public static final String REFRESH_COOKIE = "refreshCookie";
	static final String SERVER_URL = "/";

	@Bean
	OpenAPI finchOpenApi() {
		return new OpenAPI()
			.info(new Info()
				.title("FINCH API")
				.version("v1")
				.description("FINCH 공개 API. 기준 문서: docs/api/apiSpec.md")
				.contact(new Contact().name("FINCH Team"))
				.license(new License().name("Private project")))
			.servers(List.of(
				new Server().url(SERVER_URL).description("Swagger 를 연 주소 (운영 https://finchapp.org · 로컬 http://localhost:8080)")))
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
