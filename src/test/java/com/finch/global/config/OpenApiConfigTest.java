package com.finch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Swagger 서버 주소가 상대 경로 하나인지 고정한다. 절대 주소가 다시 들어오면 도메인이 바뀔 때 조용히 낡고, Swagger 를 연 호스트와
 * 다르면 "Try it out" 이 CORS 에 막힌다 ({@link OpenApiConfig} 주석).
 */
class OpenApiConfigTest {

	@Test
	@DisplayName("서버 주소는 상대 경로 / 하나다 — Swagger 를 연 주소로 요청이 간다")
	void serverIsRelative() {
		assertThat(new OpenApiConfig().finchOpenApi().getServers())
			.extracting(Server::getUrl)
			.containsExactly("/");
	}
}
