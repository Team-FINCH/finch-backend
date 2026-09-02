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
 * <p>
 * 여기서만 볼 수 있는 것이 하나 있다 — <b>화이트리스트가 실제 필터 체인에서 어떻게 갈리는지</b>다.
 * {@code @WebMvcTest} 는 컨트롤러 하나만 올리므로 actuator 경로가 존재하지 않고, 열려 있는지 막혀
 * 있는지 구분할 수 없다. 그래서 이 클래스는 앱을 통째로 띄운다 (Docker 필요).
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

	/**
	 * 위 세 개의 반대쪽. `permitAll()` 을 걷어낸 것이 실제로 효력이 있는지 본다 —
	 * 이 테스트가 200 으로 실패하면 화이트리스트가 아니라 <b>전 경로가 열려 있는</b> 것이다.
	 */
	@Test
	@DisplayName("화이트리스트 밖 경로는 토큰 없이 401 이다")
	void protectedPathRequiresToken() {
		assertThat(statusOf("/api/v1/users/me")).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	/**
	 * 매핑되지 않은 경로는 404 여야 한다 (apiSpec 11.1). `anyRequest().authenticated()` 는 존재하지 않는
	 * 경로에도 걸리므로, 토큰이 없으면 404 를 볼 수 없다 — 그래서 여기서는 없는 것을 확인하지 않고
	 * <b>인증이 먼저 걸린다</b>는 사실만 기록한다. 프론트가 404 를 기대하는 자리는 인증된 요청뿐이다.
	 */
	@Test
	@DisplayName("없는 경로도 토큰이 없으면 404 가 아니라 401 이다")
	void unmappedPathIsAuthenticatedFirst() {
		assertThat(statusOf("/api/v1/nope")).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	/** 4xx 를 예외로 바꾸지 않고 상태 코드를 그대로 본다. 401 이 나면 그 값으로 실패해야 원인이 드러난다. */
	private HttpStatusCode statusOf(String path) {
		return client.get().uri(path).exchange((request, response) -> response.getStatusCode());
	}

}
