package com.finch.domain.watchlist.repository;

import java.time.Instant;

/**
 * 관심 종목 한 행 — 등록 기록에 종목명을 붙인 읽기 전용 결과. watchlist(4층)가 {@code Stock} 엔티티를 import 하지 않고 종목명을
 * 얻는 방법이다 (backConvention 2.4 규칙 4). 시세와 보유 여부는 DB 밖이라 서비스가 포트로 붙인다.
 */
public interface WatchlistRow {

	String getStockCode();

	String getStockName();

	/**
	 * {@code KOSPI} · {@code KOSDAQ} (apiSpec 6.3, v0.8.20, 이슈 #67).
	 * <p>
	 * <b>{@code Market} enum 이 아니라 {@code String} 이다.</b> 그 타입은 {@code stock.entity} 에 있고 규칙 3 이 다른 도메인의
	 * entity 를 import 하지 말라고 한다 — 종목명을 여기서 받는 것과 같은 이유다. 값의 범위는 DB 의
	 * {@code ck_stock_market} CHECK 가 보장하고, 직렬화 결과도 enum 과 같은 {@code "KOSPI"} 다.
	 */
	String getMarket();

	/** 거래정지 여부 (apiSpec 6.3, v0.8.20). 종목 상세(§5.2)와 같은 값이다. */
	boolean isSuspended();

	Instant getRegisteredAt();
}
