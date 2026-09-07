package com.finch.domain.recent.repository;

import java.time.Instant;

/**
 * 최근 본 종목 한 행 — 기록 테이블에 종목명을 붙인 읽기 전용 결과. 인터페이스 프로젝션이라 SQL 별칭이 곧 getter 이름이다.
 * <p>
 * recent(4층)가 {@code Stock} 엔티티를 import 하지 않고 종목명을 얻는 방법이다 (backConvention 2.4 규칙 4 — 조회 전용 조인).
 * 시세는 여기 없다. DB 가 아니라 Redis 에 있어 서비스가 {@code PriceQueryPort} 로 따로 붙인다.
 */
public interface RecentViewedRow {

	String getStockCode();

	String getStockName();

	/** {@code TIMESTAMPTZ} 를 Hibernate 가 {@code Instant} 로 준다. 프로젝션 프록시는 타입을 바꾸지 않는다. */
	Instant getViewedAt();
}
