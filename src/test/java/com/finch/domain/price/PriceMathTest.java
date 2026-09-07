package com.finch.domain.price;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 등락 계산식과 반올림. 공식이 있는 곳이 한 곳이라 여기서만 본다. Docker 없이 돈다. */
class PriceMathTest {

	@Test
	@DisplayName("등락 금액은 현재가 − 기준가, 등락률은 기준가 대비 둘째 자리 HALF_UP")
	void computesChange() {
		assertThat(PriceMath.changeAmount(73_500L, 74_400L)).isEqualTo(-900L);
		// -900 / 74,400 × 100 = -1.2096… → -1.21
		assertThat(PriceMath.changeRate(73_500L, 74_400L)).isEqualByComparingTo(new BigDecimal("-1.21"));

		assertThat(PriceMath.changeAmount(51_000L, 50_000L)).isEqualTo(1_000L);
		assertThat(PriceMath.changeRate(51_000L, 50_000L)).isEqualByComparingTo(new BigDecimal("2.00"));
	}

	@Test
	@DisplayName("반올림은 HALF_UP 이고 스케일 2 를 유지한다 — 2.00 이 2.0 으로 줄지 않는다")
	void roundsHalfUp() {
		// 1 / 800 × 100 = 0.125 → 0.13
		assertThat(PriceMath.changeRate(801L, 800L)).isEqualByComparingTo(new BigDecimal("0.13"));
		assertThat(PriceMath.changeRate(50_000L, 50_000L)).isEqualByComparingTo(new BigDecimal("0.00"));
		assertThat(PriceMath.changeRate(50_000L, 50_000L).scale()).isEqualTo(2);
	}

	/** 기준가를 모르면 화면이 등락 영역을 비운다. 0% 로 답하면 실제로 변동이 없는 종목과 구분되지 않는다. */
	@Test
	@DisplayName("기준가가 없거나 0 이면 등락 둘 다 null 이다")
	void nullWhenNoBaseline() {
		assertThat(PriceMath.changeAmount(73_500L, null)).isNull();
		assertThat(PriceMath.changeRate(73_500L, null)).isNull();
		assertThat(PriceMath.changeRate(73_500L, 0L)).isNull();
		// 금액은 0 을 기준으로도 뺄 수 있다 — 나눗셈이 없다.
		assertThat(PriceMath.changeAmount(73_500L, 0L)).isEqualTo(73_500L);
	}
}
