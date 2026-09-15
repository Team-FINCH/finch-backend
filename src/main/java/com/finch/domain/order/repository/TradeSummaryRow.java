package com.finch.domain.order.repository;

import java.time.Instant;

/**
 * 내부 API 체결 한 행 — {@link TradeRepository#findInternalPage} 의 결과. 체결에 짝 원장 행의 {@code cash_balance_after} 를 붙인
 * 읽기 전용 프로젝션이다. 별칭이 곧 getter 이름이고, {@code TIMESTAMPTZ} 는 {@code Instant} 로 받는다 ({@code TransactionRow} 주석).
 */
public interface TradeSummaryRow {

	Long getTradeId();

	String getStockCode();

	/** {@code OrderSide} 문자열. DB CHECK 가 {@code BUY}·{@code SELL} 만 허용한다. */
	String getSide();

	long getPrice();

	long getQuantity();

	Instant getExecutedAt();

	long getCashBalanceAfter();
}
