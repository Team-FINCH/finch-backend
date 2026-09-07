package com.finch.domain.stock.dto.response;

import com.finch.domain.stock.port.PriceQueryPort.PriceSnapshot;
import com.finch.global.util.KstTime;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * `GET /stocks/{stockCode}/price` 응답 (apiSpec 5.4).
 * <p>
 * <b>이 엔드포인트를 stock 도메인이 소유한다.</b> 없는 종목에 {@code STOCK_NOT_FOUND} 를 내야 하는데 종목 존재 판정은 stock 만
 * 할 수 있고, price(1층)가 stock(1층)을 참조하는 것은 같은 층 참조다. 반대로 다건 조회({@code GET /stocks/prices})는 존재 판정이
 * 없어 price 가 소유한다 — 그쪽은 캐시에 없으면 "값 없음" 으로 답하면 그만이다.
 * <p>
 * 그래서 다건 응답({@code PricesRes.Item})과 필드가 같지만 타입이 둘이다. 합치려면 한쪽이 다른 층의 DTO 를 import 해야 한다.
 * JSON 은 같은 모양으로 나가므로 프론트에는 차이가 없다.
 *
 * @param stale 값이 오래됐거나 아예 없다 (apiSpec 5.4 의 세 상태). 값 없음이면 시세 넷이 전부 null 이다.
 */
public record StockPriceRes(String stockCode, Long currentPrice, Long changeAmount, BigDecimal changeRate,
	OffsetDateTime asOf, boolean stale) {

	public static StockPriceRes of(String stockCode, PriceSnapshot price) {
		return new StockPriceRes(stockCode, price.currentPrice(), price.changeAmount(), price.changeRate(),
			price.asOf() == null ? null : KstTime.toResponse(price.asOf()), price.stale());
	}
}
