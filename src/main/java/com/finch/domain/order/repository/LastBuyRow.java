package com.finch.domain.order.repository;

import java.time.Instant;

/** 종목별 마지막 매수 체결 한 행 — {@link TradeRepository#findLastBuys} 의 결과. 읽기 전용 프로젝션이다. */
public interface LastBuyRow {

	String getStockCode();

	Long getTradeId();

	Instant getExecutedAt();
}
