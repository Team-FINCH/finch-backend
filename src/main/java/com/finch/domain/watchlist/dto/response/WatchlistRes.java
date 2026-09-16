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
 *                 상장폐지 종목은 목록에서 빠지고 이 숫자에도 들어가지 않으며, 한도 판정도 같은 기준이다.
 * @param maxCount 상한 50. 서버가 정한 값이라 프론트가 상수로 갖지 않는다.
 */
public record WatchlistRes(int count, int maxCount, List<Item> items) {

	/**
	 * @param market    {@code KOSPI} · {@code KOSDAQ} (v0.8.20, 이슈 #67). 종목 상세(§5.2)와 같은 값이고 화면은 종목명 아래
	 *                  {@code 종목코드 · 시장} 을 적는다. 타입이 {@code String} 인 이유는 {@link WatchlistRow#getMarket()} 에 있다.
	 * @param suspended 거래정지면 true → 화면에 거래정지 태그 (v0.8.20, 이슈 #67).
	 *                  <b>시세 셋과 달리 이 값은 폴링으로 갱신되지 않는다.</b> 프론트는 목록을 한 번 받고 {@code GET /stocks/prices} 로
	 *                  시세만 주기적으로 덮어쓰는데, 거기에는 이 필드가 없다. 장중에 정지가 걸리면 화면을 다시 열 때 반영된다.
	 * @param held      보유 중이면 true → 화면에 "보유" 뱃지 (featureSpec 6장). S8 전에는 언제나 false 다.
	 */
	public record Item(String stockCode, String stockName, String market, boolean suspended, Long currentPrice,
		Long changeAmount, BigDecimal changeRate, boolean held, OffsetDateTime registeredAt) {

		public static Item of(WatchlistRow row, PriceSnapshot price, boolean held) {
			return new Item(row.getStockCode(), row.getStockName(), row.getMarket(), row.isSuspended(),
				price.currentPrice(), price.changeAmount(), price.changeRate(), held,
				KstTime.toResponse(row.getRegisteredAt()));
		}
	}
}
