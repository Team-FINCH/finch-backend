package com.finch.domain.stock.event;

/**
 * 사용자가 종목을 검색했다. {@code StockService.search} 가 발행하고 recent 도메인(S6)이 받아 최근 검색어에 기록한다
 * (apiSpec 6.2 — 계정 기준 서버 저장). 이유는 {@link StockViewedEvent} 와 같다.
 * <p>
 * 검증을 통과한 검색만 발행한다 — 2글자 미만은 400 이라 검색어가 아니다.
 */
public record StockSearchedEvent(Long userId, String keyword) {
}
