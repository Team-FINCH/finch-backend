package com.finch.domain.ai.dto.response;

import com.finch.domain.portfolio.dto.response.PortfolioRes;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * `GET /internal/v1/portfolio` (apiSpec 9.1). <b>원본 값만</b> — 평가금액·손익·수익률은 내려주지 않는다. 파생 지표는 AI 가 계산한다는
 * 분담(S0-5 초안, aiApiSpec §4.1)이고, 백엔드는 "지금 무엇을 얼마나 들고 있나" 만 준다.
 * <p>
 * {@code GET /portfolio} 의 응답({@link PortfolioRes})에서 필요한 필드만 뽑는다. 같은 평가 엔진을 지나므로 화면과 AI 가 보는
 * 현재가가 같다. {@code currentPrice} 는 시세가 없으면 null 이다 (apiSpec 5.4 "값 없음").
 */
public record InternalPortfolioRes(long cashBalance, OffsetDateTime asOf, List<Holding> holdings) {

	public static InternalPortfolioRes from(PortfolioRes portfolio) {
		return new InternalPortfolioRes(portfolio.cashBalance(), portfolio.asOf(),
			portfolio.holdings().stream().map(Holding::from).toList());
	}

	public record Holding(String stockCode, String stockName, long quantity, long avgBuyPrice, Long currentPrice) {

		static Holding from(PortfolioRes.Holding h) {
			return new Holding(h.stockCode(), h.stockName(), h.quantity(), h.avgBuyPrice(), h.currentPrice());
		}
	}
}
