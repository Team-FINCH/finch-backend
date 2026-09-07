package com.finch.domain.stock.event;

import java.time.Instant;

/**
 * 사용자가 종목 상세를 봤다. {@code StockService.detail} 이 발행하고 recent 도메인(S6)이 {@code @EventListener} 로 받아
 * 최근 본 종목에 기록한다 (apiSpec 5.2 "이 API 호출 시 서버가 최근 본 종목에 자동 기록").
 * <p>
 * 직접 호출이 아니라 이벤트인 이유 — stock(1층)이 recent(4층)를 부르면 역방향 참조다 (backConvention 2.4 규칙 2).
 * 상세 조회는 "알려만" 주면 되고 결과를 기다릴 필요가 없다. 동기 리스너로 충분하다 — UPSERT 한 건이다.
 */
public record StockViewedEvent(Long userId, String stockCode, Instant viewedAt) {
}
