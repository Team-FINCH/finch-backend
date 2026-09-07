package com.finch.domain.watchlist.repository;

import java.time.Instant;

/**
 * 관심 종목 한 행 — 등록 기록에 종목명을 붙인 읽기 전용 결과. watchlist(4층)가 {@code Stock} 엔티티를 import 하지 않고 종목명을
 * 얻는 방법이다 (backConvention 2.4 규칙 4). 시세와 보유 여부는 DB 밖이라 서비스가 포트로 붙인다.
 */
public interface WatchlistRow {

	String getStockCode();

	String getStockName();

	Instant getRegisteredAt();
}
