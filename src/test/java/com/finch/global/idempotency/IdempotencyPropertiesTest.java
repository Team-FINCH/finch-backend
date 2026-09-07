package com.finch.global.idempotency;

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
 * <b>배포되는 {@code application.yaml} 의 값</b>이 apiSpec 과 맞는지 본다. 다른 테스트들은 프로퍼티를
 * 테스트용으로 덮어쓰기 때문에 실제 기본값이 틀려도 전부 통과한다 — 그 빈틈을 메우는 자리다.
 * <p>
 * 특히 {@code paths} 는 <b>빠뜨려도 아무 데서도 실패하지 않는다.</b> 목록에 없는 경로는 필터가 그냥
 * 지나치므로, 그 엔드포인트는 에러 없이 <b>멱등성만 사라진 채</b> 열린다. 실제로 이 MR 의 첫 판에서
 * {@code /api/v1/deposits} 가 빠져 있었고 프론트 리뷰에서 발견됐다. 그래서 명세의 목록을 여기 옮겨 적는다.
 */
class IdempotencyPropertiesTest {

	private final IdempotencyProperties properties = bindFromApplicationYaml();

	/**
	 * apiSpec v0.8 §1.4·§4.5·§7.1·§11.1·§12 가 주문과 출금을 멱등성 키 필수로 적고 있다.
	 * 충전은 v0.8 에서 빠졌다 — 멱등 기준이 PG 가 발급한 {@code paymentKey} 로 바뀌었고(§4.4), 결제창을 거쳐
	 * 돌아온 요청은 최초 호출과 다른 세션일 수 있어 클라이언트 UUID 로는 판정할 수 없다.
	 * 명세를 고치는 MR 이 이 테스트도 함께 고쳐야 한다. 그 마찰이 의도다.
	 */
	@Test
	@DisplayName("멱등성 검사 경로는 apiSpec 이 키 필수로 정한 주문·출금 둘이다 — 충전은 paymentKey 멱등이라 없다")
	void pathsMatchSpec() {
		assertThat(properties.paths())
			.containsExactlyInAnyOrder("/api/v1/orders", "/api/v1/withdrawals");
	}

	/** 충전 경로가 다시 들어오면 ready 가 헤더 없음 400 으로 막힌다. 이름을 못 박아 둔다. */
	@Test
	@DisplayName("충전 경로는 목록에 없다")
	void depositPathsAreExcluded() {
		assertThat(properties.paths()).noneMatch(path -> path.startsWith("/api/v1/deposits"));
	}

	@Test
	@DisplayName("키 보관 기간은 apiSpec 1.4 가 확정한 24시간이다")
	void doneTtlIsTwentyFourHours() {
		assertThat(properties.doneTtl()).isEqualTo(Duration.ofHours(24));
	}

	@Test
	@DisplayName("처리 중 표시는 24시간이 아니라 짧게 잡는다 — 서버가 죽어도 곧 풀려야 한다")
	void inProgressTtlIsShort() {
		assertThat(properties.inProgressTtl()).isEqualTo(Duration.ofSeconds(60));
		assertThat(properties.inProgressTtl()).isLessThan(properties.doneTtl());
	}

	@Test
	@DisplayName("Retry-After 는 초 단위 정수이고 0 이 되지 않는다")
	void retryAfterIsPositiveSeconds() {
		assertThat(properties.retryAfterSeconds()).isEqualTo(1);
		// 1초 미만으로 설정해도 0 이 나가면 클라이언트가 쉬지 않고 되풀이한다.
		assertThat(new IdempotencyProperties(List.of(), Duration.ofSeconds(60), Duration.ofHours(24),
			Duration.ofMillis(200)).retryAfterSeconds()).isEqualTo(1);
	}

	/** 스프링 컨텍스트를 띄우지 않고 실제 yaml 파일만 읽어 바인딩한다. Docker 없이 돈다. */
	private static IdempotencyProperties bindFromApplicationYaml() {
		MutablePropertySources sources = new MutablePropertySources();
		for (PropertySource<?> source : load()) {
			sources.addLast(source);
		}
		return new Binder(ConfigurationPropertySources.from(sources))
			.bind("finch.idempotency", IdempotencyProperties.class)
			.orElseThrow(() -> new IllegalStateException("application.yaml 에 finch.idempotency 가 없다"));
	}

	private static List<PropertySource<?>> load() {
		try {
			return new YamlPropertySourceLoader()
				.load("application.yaml", new ClassPathResource("application.yaml"));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
