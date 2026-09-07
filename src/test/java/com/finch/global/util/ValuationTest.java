package com.finch.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 평가 계산의 경계. 세 화면이 이 식 하나를 쓰므로 여기가 틀리면 잔고·계좌 요약·종목 상세가 동시에 틀린다.
 */
class ValuationTest {

	@Test
	@DisplayName("평가금액은 수량 × 현재가")
	void evaluatesAmount() {
		assertThat(Valuation.evaluationAmount(10, 70_000)).isEqualTo(700_000);
	}

	@Test
	@DisplayName("이익이면 양수, 손실이면 음수 — 손실을 0 으로 자르지 않는다")
	void signsProfit() {
		assertThat(Valuation.evaluationProfit(10, 60_000, 70_000)).isEqualTo(100_000);
		assertThat(Valuation.evaluationProfit(10, 70_000, 60_000)).isEqualTo(-100_000);
	}

	@Test
	@DisplayName("수익률은 매입 원가 대비 백분율, 소수 둘째 자리")
	void computesRate() {
		assertThat(Valuation.evaluationProfitRate(10, 60_000, 70_000)).isEqualByComparingTo("16.67");
		assertThat(Valuation.evaluationProfitRate(10, 70_000, 60_000)).isEqualByComparingTo("-14.29");
	}

	@Test
	@DisplayName("셋째 자리는 HALF_UP 으로 올린다 — .005 가 내려가면 백엔드와 화면의 반올림이 갈린다")
	void roundsHalfUp() {
		// 손익 1,005 / 원가 100,000 = 1.005% → 1.01
		assertThat(Valuation.evaluationProfitRate(1, 100_000, 101_005)).isEqualByComparingTo("1.01");
		// 음수도 절댓값 기준으로 올린다 (HALF_UP 은 0 에서 멀어지는 방향이다) — −1.005% → −1.01
		assertThat(Valuation.evaluationProfitRate(1, 100_000, 98_995)).isEqualByComparingTo("-1.01");
	}

	@Test
	@DisplayName("소수가 없어도 자리수는 둘로 고정한다 — 화면이 자리수를 맞출 필요가 없다")
	void keepsScale() {
		assertThat(Valuation.evaluationProfitRate(10, 10_000, 11_000).toPlainString()).isEqualTo("10.00");
	}

	@Test
	@DisplayName("수량이 0 이면 평가도 0 이고 수익률은 0.00 — 0 으로 나누지 않는다")
	void handlesZeroQuantity() {
		assertThat(Valuation.evaluationAmount(0, 70_000)).isZero();
		assertThat(Valuation.evaluationProfit(0, 60_000, 70_000)).isZero();
		assertThat(Valuation.evaluationProfitRate(0, 60_000, 70_000)).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	@DisplayName("평단이 0 이어도 수익률은 0.00 — 분모가 0 인 유일한 다른 경로다")
	void handlesZeroAvgPrice() {
		assertThat(Valuation.evaluationProfitRate(10, 0, 70_000)).isEqualByComparingTo(BigDecimal.ZERO);
	}
}
