package com.finch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * <b>배포되는 {@code application.yaml} 의 커넥션 풀 값</b>을 본다 ({@code IdempotencyPropertiesTest} 와 같은 이유 —
 * 다른 테스트는 프로퍼티를 덮어쓰므로 실제 기본값이 틀려도 전부 통과한다).
 * <p>
 * 지키려는 것은 값 하나가 아니라 <b>부등식</b>이다. {@code maxIdleTime} 이 상대 서버의 keep-alive 보다 길어지는 순간,
 * 서버가 이미 닫은 연결을 풀에서 꺼내 쓰고 {@code Connection prematurely closed BEFORE response} 가 난다. 그 실패는
 * <b>간헐적</b>이라 (요청 간격이 서버 keep-alive 를 넘길 때만 난다) 테스트 없이는 리뷰에서 잡히지 않는다. 실제로
 * 이슈 207 이 그렇게 났다 — 운영에서 AI 분석·채팅이 502 로 떨어졌고, 원인이 풀 설정이라는 것을 로그를 파서야 알았다.
 */
class HttpPoolPropertiesTest {

	/**
	 * AI 서버의 uvicorn {@code --timeout-keep-alive} 기본값. {@code infra/docker/ai.Dockerfile} 의 {@code CMD} 가 이 옵션을
	 * 주지 않으므로 기본값이 그대로 산다. <b>여기 있는 다른 상대(KIS·카카오)는 더 길다</b> — 가장 짧은 쪽에 맞추면 나머지는
	 * 저절로 안전하다. AI 가 이 값을 명시적으로 바꾸면 그때 이 상수를 함께 고친다.
	 */
	private static final Duration UVICORN_KEEP_ALIVE = Duration.ofSeconds(5);

	private final FinchProperties.Http http = bindFromApplicationYaml();

	@Test
	@DisplayName("유휴 연결 폐기는 상대 서버 keep-alive 보다 짧다 — 길면 죽은 연결을 재사용해 간헐적 502 가 난다")
	void maxIdleTimeIsShorterThanServerKeepAlive() {
		assertThat(http.maxIdleTime())
			.as("uvicorn 기본 keep-alive(%s)보다 짧아야 우리가 먼저 연결을 버린다", UVICORN_KEEP_ALIVE)
			.isLessThan(UVICORN_KEEP_ALIVE);
	}

	@Test
	@DisplayName("유휴 연결 폐기가 켜져 있다 — 0 이면 Reactor 기본값(무기한 보관)으로 돌아간다")
	void idleEvictionIsEnabled() {
		assertThat(http.maxIdleTime()).isPositive();
		assertThat(http.evictInterval()).isPositive();
	}

	/**
	 * 연결 타임아웃은 응답 대기 시간이 아니다. 늘리고 싶어지면 {@code finch.ai.timeout} 처럼 도메인 쪽 값을 보는 것이
	 * 맞다 ({@code FinchProperties.Http} 주석).
	 */
	@Test
	@DisplayName("연결 타임아웃은 3초다")
	void connectTimeout() {
		assertThat(http.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
	}

	private static FinchProperties.Http bindFromApplicationYaml() {
		try {
			List<PropertySource<?>> sources =
				new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
			MutablePropertySources merged = new MutablePropertySources();
			sources.forEach(merged::addLast);
			return new Binder(ConfigurationPropertySources.from(merged))
				.bind("finch.http", FinchProperties.Http.class)
				.orElseThrow(() -> new IllegalStateException("finch.http 를 읽지 못했다"));
		} catch (IOException e) {
			throw new IllegalStateException("application.yaml 을 읽지 못했다", e);
		}
	}
}
