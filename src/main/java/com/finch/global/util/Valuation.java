package com.finch.global.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 보유 평가 계산 (apiSpec 3.1·8.1, featureSpec 0장 용어 정의). <b>공식이 있는 곳은 여기 하나다.</b>
 * <p>
 * 세 자리에서 같은 식을 쓴다 — 보유 목록({@code GET /portfolio}), 계좌 요약의 평가금액({@code GET /account}),
 * 종목 상세의 보유 카드({@code GET /stocks/{code}} 의 {@code holding}). 흩어지면 반올림이나 0 처리가 화면마다 갈리고,
 * 같은 종목의 수익률이 두 화면에서 다르게 보인다.
 * <p>
 * <b>{@code global} 에 두는 이유.</b> 쓰는 쪽이 stock(1층)·account(2층)·portfolio(3층)에 걸쳐 있어 어느 도메인에 두어도
 * 나머지가 참조 규칙을 어긴다 (backConvention 2.4 규칙 2). 도메인 타입을 하나도 쓰지 않는 순수 산술이라
 * "global 은 도메인을 참조하지 않는다"(규칙 1)에도 어긋나지 않는다. {@code KstTime} 과 같은 자리다.
 * <p>
 * <b>{@code null} 은 다루지 않는다.</b> 시세가 없는 종목을 어떻게 표현할지는 화면 계약이고(apiSpec 8.1 — 평가 필드 null),
 * 그 판단은 호출자가 한다. 여기 들어오는 값은 전부 확정된 숫자다.
 */
public final class Valuation {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private Valuation() {
	}

	/** 평가금액 = 보유 수량 × 현재가. */
	public static long evaluationAmount(long quantity, long currentPrice) {
		return quantity * currentPrice;
	}

	/** 평가손익 = (현재가 − 평균 매수가) × 보유 수량. 손실이면 음수다. */
	public static long evaluationProfit(long quantity, long avgBuyPrice, long currentPrice) {
		return (currentPrice - avgBuyPrice) * quantity;
	}

	/**
	 * 평가수익률 = 평가손익 ÷ (평균 매수가 × 보유 수량) × 100. 소수 둘째 자리 HALF_UP (backConvention 6장).
	 * <p>
	 * <b>분모가 0 이면 0.00 이다.</b> 평균 매수가나 수량이 0 인 보유는 실제로는 생기지 않는다 — 체결가는 0 보다 크고
	 * ({@code ck_trade_executed_price}) 수량 0 인 행은 목록에서 걸러진다. 그래도 0 을 나누지 않도록 값을 정해 둔다.
	 */
	public static BigDecimal evaluationProfitRate(long quantity, long avgBuyPrice, long currentPrice) {
		long cost = avgBuyPrice * quantity;
		if (cost == 0) {
			return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
		}
		return BigDecimal.valueOf(evaluationProfit(quantity, avgBuyPrice, currentPrice))
			.multiply(HUNDRED)
			.divide(BigDecimal.valueOf(cost), 2, RoundingMode.HALF_UP);
	}
}
