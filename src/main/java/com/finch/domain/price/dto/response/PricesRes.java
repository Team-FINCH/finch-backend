package com.finch.domain.price.dto.response;

import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.global.util.KstTime;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * `GET /stocks/prices` 응답 (apiSpec 5.5). 관심 목록·보유 목록이 N+1 호출을 하지 않게 하는 벌크 조회다.
 * <p>
 * <b>요청한 모든 종목이 {@code items} 에 담긴다.</b> 캐시에 없는 종목도 "값 없음"(시세 넷 null, {@code stale: true})으로
 * 들어간다 — 빠뜨리면 프론트가 어느 종목이 왜 없는지 따로 따져야 한다. 존재하지 않는 종목코드를 보내도 같다.
 * <p>
 * 항목 모양이 단건 응답({@code StockPriceRes})과 같지만 타입이 둘인 이유는 그쪽 주석에 있다.
 */
public record PricesRes(List<Item> items) {

	public record Item(String stockCode, Long currentPrice, Long changeAmount, BigDecimal changeRate,
		OffsetDateTime asOf, boolean stale) {

		public static Item of(String stockCode, PriceSnapshot price) {
			return new Item(stockCode, price.currentPrice(), price.changeAmount(), price.changeRate(),
				price.asOf() == null ? null : KstTime.toResponse(price.asOf()), price.stale());
		}
	}
}
