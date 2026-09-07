package com.finch.domain.order.dto.response;

import com.finch.domain.order.entity.OrderSide;
import com.finch.domain.order.entity.Trade;
import com.finch.global.util.KstTime;
import java.time.OffsetDateTime;

/**
 * 체결 한 건의 요약. AI 서버 내부 API(apiSpec 9.2)가 내려주는 모양이고, order 가 다른 도메인(ai)에 체결을 보여주는 유일한 DTO 다 —
 * {@code Trade} 엔티티는 밖으로 나가지 않는다 (backConvention 2.4 규칙 3).
 * <p>
 * 원본 값만 있다. 실현손익·수익률 같은 파생 지표는 AI 가 계산한다 (apiSpec 9 "S0-5: 원본 값만 내려주고 AI 가 계산").
 *
 * @param tradeId {@code trade.id}. 커서의 기준이다.
 * @param price   체결가 ({@code executed_price}).
 */
public record TradeSummaryRes(Long tradeId, String stockCode, OrderSide side, long price, long quantity,
	OffsetDateTime executedAt) {

	public static TradeSummaryRes from(Trade trade) {
		return new TradeSummaryRes(trade.getId(), trade.getStockCode(), trade.getSide(), trade.getExecutedPrice(),
			trade.getQuantity(), KstTime.toResponse(trade.getExecutedAt()));
	}
}
