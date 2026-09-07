package com.finch.domain.stock.dto.response;

import com.finch.domain.stock.entity.Market;
import com.finch.domain.stock.entity.Stock;
import com.finch.domain.stock.port.HoldingQueryPort.HoldingSnapshot;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.global.util.KstTime;
import com.finch.global.util.Valuation;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * `GET /stocks/{stockCode}` 응답 (apiSpec 5.2).
 *
 * @param asOf    시세 기준 시각. 값이 없으면 null (apiSpec 5.4).
 * @param holding 보유하지 않으면 <b>null</b> — 키는 있다. 전량 매도 뒤 0 수량 행도 null 이다 (contracts C76, 판정은 포트 계약).
 */
public record StockDetailRes(
	String stockCode,
	String stockName,
	Market market,
	Long currentPrice,
	Long previousClose,
	Long changeAmount,
	BigDecimal changeRate,
	boolean suspended,
	String suspendedReason,
	boolean watched,
	OffsetDateTime asOf,
	Holding holding
) {

	public static StockDetailRes of(Stock stock, PriceSnapshot price, boolean watched, Optional<HoldingSnapshot> holding) {
		return new StockDetailRes(stock.getStockCode(), stock.getStockName(), stock.getMarket(), price.currentPrice(),
			stock.getPreviousClose(), price.changeAmount(), price.changeRate(), stock.isSuspended(),
			stock.getSuspendedReason(), watched, price.asOf() == null ? null : KstTime.toResponse(price.asOf()),
			holding.map(h -> Holding.of(h, price.currentPrice())).orElse(null));
	}

	/**
	 * 보유 요약 카드 (featureSpec 7.1). 계산은 {@link Valuation} 이 한다 — 잔고 화면과 같은 식이어야 한 종목의 수익률이 두 화면에서
	 * 다르게 보이지 않는다. <b>S8 이 여기 있던 식을 그쪽으로 옮겼다.</b>
	 * <p>
	 * 현재가가 없으면(캐시 미스) 평가 둘 다 null — 수량·평단은 시세와 무관하게 보여준다.
	 */
	public record Holding(long quantity, long avgBuyPrice, Long evaluationProfit, BigDecimal evaluationProfitRate) {

		public static Holding of(HoldingSnapshot snapshot, Long currentPrice) {
			if (currentPrice == null) {
				return new Holding(snapshot.quantity(), snapshot.avgBuyPrice(), null, null);
			}
			return new Holding(snapshot.quantity(), snapshot.avgBuyPrice(),
				Valuation.evaluationProfit(snapshot.quantity(), snapshot.avgBuyPrice(), currentPrice),
				Valuation.evaluationProfitRate(snapshot.quantity(), snapshot.avgBuyPrice(), currentPrice));
		}
	}
}
