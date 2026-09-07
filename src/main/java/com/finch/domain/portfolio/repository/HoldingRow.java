package com.finch.domain.portfolio.repository;

/**
 * 보유 종목 한 행 — 보유 기록에 종목명을 붙인 읽기 전용 결과. portfolio(3층)가 {@code Stock} 엔티티를 import 하지 않고 종목명을
 * 얻는 방법이다 (backConvention 2.4 규칙 4 — 조회 전용 조인). 시세는 DB 밖이라 서비스가 포트로 붙인다.
 */
public interface HoldingRow {

	String getStockCode();

	String getStockName();

	long getQuantity();

	long getAvgBuyPrice();
}
