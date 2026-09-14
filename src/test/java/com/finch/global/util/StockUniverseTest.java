package com.finch.global.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.global.config.FinchProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 범위 판정 규칙. 꺼져 있으면 전부 통과, 켜져 있으면 목록만, 켰는데 비어 있으면 기동 실패. */
class StockUniverseTest {

	@Test
	@DisplayName("꺼져 있으면 어떤 코드든 포함이고 filter 는 그대로 돌려준다")
	void unrestrictedPassesEverything() {
		StockUniverse universe = StockUniverse.unrestricted();

		assertThat(universe.restricted()).isFalse();
		assertThat(universe.contains("000150")).isTrue();
		assertThat(universe.filter(List.of("000150", "005930"))).containsExactly("000150", "005930");
		assertThat(universe.codes()).isEmpty();
	}

	@Test
	@DisplayName("켜져 있으면 목록 안만 포함이고 filter 는 순서를 지켜 범위 밖을 뺀다")
	void restrictedKeepsOnlyListed() {
		StockUniverse universe = new StockUniverse(new FinchProperties.Universe(true, List.of("005930", "000660", "005930")));

		assertThat(universe.restricted()).isTrue();
		assertThat(universe.contains("005930")).isTrue();
		assertThat(universe.contains("000150")).isFalse();
		assertThat(universe.filter(List.of("000150", "000660", "005930"))).containsExactly("000660", "005930");
		// 중복은 걷어낸다 — 같은 종목을 두 번 적어도 목록은 하나다.
		assertThat(universe.codes()).containsExactlyInAnyOrder("005930", "000660");
	}

	@Test
	@DisplayName("켰는데 목록이 비어 있으면 설정 오류다 — 조용히 전 종목이 되지 않는다")
	void enabledWithoutCodesFails() {
		assertThatThrownBy(() -> new FinchProperties.Universe(true, List.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("codes");
	}
}
