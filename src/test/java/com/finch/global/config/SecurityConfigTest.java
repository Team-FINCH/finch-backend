package com.finch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * 쿠버네티스 프로브와 Prometheus 는 인증 헤더를 붙이지 않는다.
 * 이 경로들이 막히면 배포가 롤백되거나 모니터링이 조용히 비는데,
 * 둘 다 애플리케이션 로그에는 아무 흔적을 남기지 않는다.
 * SecurityConfig 의 규칙을 바꿀 때 이 테스트가 그 사고를 막는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigTest {

	@LocalServerPort
	private int port;

	private RestClient client;

	@BeforeEach
	void setUp() {
		client = RestClient.builder().baseUrl("http://localhost:" + port).build();
	}

	@Test
	@DisplayName("readinessProbe 경로는 인증 없이 200 을 준다")
	void readinessProbeIsOpen() {
		assertThat(statusOf("/actuator/health/readiness")).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("livenessProbe 경로는 인증 없이 200 을 준다")
	void livenessProbeIsOpen() {
		assertThat(statusOf("/actuator/health/liveness")).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("Prometheus scrape 경로는 인증 없이 200 을 준다")
	void prometheusEndpointIsOpen() {
		assertThat(statusOf("/actuator/prometheus")).isEqualTo(HttpStatus.OK);
	}

	/** 4xx 를 예외로 바꾸지 않고 상태 코드를 그대로 본다. 401 이 나면 그 값으로 실패해야 원인이 드러난다. */
	private HttpStatusCode statusOf(String path) {
		return client.get().uri(path).exchange((request, response) -> response.getStatusCode());
	}

}
