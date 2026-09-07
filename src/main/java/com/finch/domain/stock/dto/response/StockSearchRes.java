package com.finch.domain.stock.dto.response;

import com.finch.domain.stock.entity.Market;
import com.finch.domain.stock.entity.Stock;
import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import java.math.BigDecimal;
import java.util.List;

/**
 * `GET /stocks/search` 응답 (apiSpec 5.1). 결과 없음은 빈 {@code items} 이지 에러가 아니다.
 * 시세 셋은 캐시 미스면 null 이다 (apiSpec 5.4 셋째 행) — 검색 결과에 {@code stale} 은 없다.
 */
public record StockSearchRes(List<Item> items) {

	public record Item(String stockCode, String stockName, Market market, Long currentPrice, Long changeAmount,
		BigDecimal changeRate, boolean suspended) {

		public static Item of(Stock stock, PriceSnapshot price) {
			return new Item(stock.getStockCode(), stock.getStockName(), stock.getMarket(), price.currentPrice(),
				price.changeAmount(), price.changeRate(), stock.isSuspended());
		}
	}
}
