package com.finch.domain.order.dto.response;

import com.finch.domain.order.repository.LastBuyRow;
import java.time.Instant;

/**
 * 종목 하나의 마지막 매수 체결. 다른 도메인이 {@code trade} 를 직접 읽지 않고 받는 모양이다 (backConvention 2.4 규칙 3).
 *
 * @param tradeId    체결 id — apiSpec 7.1 의 {@code orderId}, 8.2 의 {@code tradeId} 와 같은 값.
 * @param executedAt 체결 시각.
 */
public record LastBuyRes(String stockCode, Long tradeId, Instant executedAt) {

	public static LastBuyRes from(LastBuyRow row) {
		return new LastBuyRes(row.getStockCode(), row.getTradeId(), row.getExecutedAt());
	}
}
