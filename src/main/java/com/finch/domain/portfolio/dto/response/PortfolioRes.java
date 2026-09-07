package com.finch.domain.portfolio.dto.response;

import com.finch.domain.portfolio.repository.HoldingRow;
import com.finch.global.util.KstTime;
import com.finch.global.util.Valuation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * `GET /portfolio` 응답 (apiSpec 8.1). 상단 요약 셋은 `GET /account` 와 같은 값이다 — 화면이 두 API 를 따로 부르지 않아도 되게
 * 함께 내려준다 (featureSpec 9.1·9.2 가 한 화면이다).
 *
 * @param asOf     시세 기준 시각. 보유 종목들의 {@code asOf} 중 <b>가장 오래된 값</b>이다 — 화면의 "갱신 시각" 은 가장 보수적인
 *                 값이어야 사용자가 실제보다 신선하다고 오해하지 않는다.
 * @param holdings 보유 수량이 1 이상인 종목만. 전량 매도로 남은 0 수량 행은 내부 구현이라 나가지 않는다 (erd.md §2.6).
 */
public record PortfolioRes(long cashBalance, long evaluationAmount, long totalAsset, OffsetDateTime asOf,
	List<Holding> holdings) {

	public static PortfolioRes of(long cashBalance, long evaluationAmount, Instant asOf, List<Holding> holdings) {
		return new PortfolioRes(cashBalance, evaluationAmount, cashBalance + evaluationAmount,
			KstTime.toResponse(asOf), holdings);
	}

	/**
	 * @param currentPrice         시세가 없으면 null 이고, 그때 평가 셋도 전부 null 이다 (apiSpec 8.1 — v0.8.2 에서 명문화).
	 *                             수량과 평단은 시세와 무관하게 언제나 나간다.
	 * @param evaluationProfitRate 소수 둘째 자리 HALF_UP. 계산은 {@link Valuation} 한 곳에서 한다.
	 */
	public record Holding(String stockCode, String stockName, long quantity, long avgBuyPrice, Long currentPrice,
		Long evaluationAmount, Long evaluationProfit, BigDecimal evaluationProfitRate) {

		public static Holding of(HoldingRow row, Long currentPrice) {
			if (currentPrice == null) {
				return new Holding(row.getStockCode(), row.getStockName(), row.getQuantity(), row.getAvgBuyPrice(),
					null, null, null, null);
			}
			return new Holding(row.getStockCode(), row.getStockName(), row.getQuantity(), row.getAvgBuyPrice(),
				currentPrice,
				Valuation.evaluationAmount(row.getQuantity(), currentPrice),
				Valuation.evaluationProfit(row.getQuantity(), row.getAvgBuyPrice(), currentPrice),
				Valuation.evaluationProfitRate(row.getQuantity(), row.getAvgBuyPrice(), currentPrice));
		}
	}
}
