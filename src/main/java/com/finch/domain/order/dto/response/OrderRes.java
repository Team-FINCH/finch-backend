package com.finch.domain.order.dto.response;

import com.finch.domain.order.entity.OrderSide;
import com.finch.domain.order.entity.Trade;
import com.finch.global.util.KstTime;
import java.time.OffsetDateTime;

/**
 * `POST /orders` 응답 (apiSpec 7.1). 같은 {@code Idempotency-Key} 로 다시 오면 이 본문이 필터의 저장본으로 그대로 재생된다.
 *
 * @param orderId          {@code trade.id}. 별도 주문 테이블이 없다 (erd.md §2.5).
 * @param executedPrice    체결가. 확인 화면의 예상가와 다를 수 있다 (featureSpec 7.3).
 * @param cashBalanceAfter 체결 직후 예수금. 원장 행의 {@code cash_balance_after} 와 같은 값이다.
 * @param realizedProfit   매도일 때만 값이 있다. 매수는 {@code null} 이고 필드는 남는다 — 계약이 그렇다.
 * @param executedAt       원장 행의 {@code occurred_at}. KST 오프셋 표기다 (apiSpec 1.1).
 */
public record OrderRes(
	Long orderId,
	String stockCode,
	String stockName,
	OrderSide side,
	long quantity,
	long executedPrice,
	long executedAmount,
	OffsetDateTime executedAt,
	long cashBalanceAfter,
	Long realizedProfit
) {

	public static OrderRes of(Trade trade, String stockName, long cashBalanceAfter) {
		return new OrderRes(trade.getId(), trade.getStockCode(), stockName, trade.getSide(), trade.getQuantity(),
			trade.getExecutedPrice(), trade.getExecutedAmount(), KstTime.toResponse(trade.getExecutedAt()),
			cashBalanceAfter, trade.getRealizedProfit());
	}
}
