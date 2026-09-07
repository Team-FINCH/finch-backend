package com.finch.domain.watchlist.dto.response;

import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.domain.watchlist.repository.WatchlistRow;
import com.finch.global.util.KstTime;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * `GET /watchlist` 응답 (apiSpec 6.3).
 *
 * @param count    지금 담긴 개수. {@code items.size()} 와 같지만 함께 내려준다 — 화면이 "12 / 50" 을 그릴 때 배열을 세지 않게 한다.
 * @param maxCount 상한 50. 서버가 정한 값이라 프론트가 상수로 갖지 않는다.
 */
public record WatchlistRes(int count, int maxCount, List<Item> items) {

	/**
	 * @param held 보유 중이면 true → 화면에 "보유" 뱃지 (featureSpec 6장). S8 전에는 언제나 false 다.
	 */
	public record Item(String stockCode, String stockName, Long currentPrice, Long changeAmount, BigDecimal changeRate,
		boolean held, OffsetDateTime registeredAt) {

		public static Item of(WatchlistRow row, PriceSnapshot price, boolean held) {
			return new Item(row.getStockCode(), row.getStockName(), price.currentPrice(), price.changeAmount(),
				price.changeRate(), held, KstTime.toResponse(row.getRegisteredAt()));
		}
	}
}
