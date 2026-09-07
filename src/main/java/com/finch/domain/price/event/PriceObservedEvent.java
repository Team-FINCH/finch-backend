package com.finch.domain.price.event;

/**
 * KIS 현재가 응답에 종목 마스터가 알아야 할 값이 실려 왔다 — 기준가(전일 종가)와 거래정지.
 * <p>
 * price(1층)가 stock(1층)을 직접 고칠 수 없다 (backConvention 2.4 규칙 2 — 같은 층 참조 금지). 그래서 이벤트로 알리고 stock 의
 * {@code PriceObservedListener} 가 받아 {@code stock.previous_close}·{@code suspended} 를 갱신한다. {@code StockViewedEvent} 와 같은 방식이다.
 * <p>
 * <b>값이 바뀐 종목만 발행한다</b> ({@code KisPollingFeed}). 3초마다 관심 종목 전부를 발행하면 그만큼 UPDATE 가 나간다.
 *
 * @param previousClose   KIS 기준가. 모르면 null 이고 그때는 갱신하지 않는다.
 * @param suspendedReason 거래정지·관리종목 등 사유. 없으면 null.
 */
public record PriceObservedEvent(String stockCode, Long previousClose, boolean suspended, String suspendedReason) {
}
