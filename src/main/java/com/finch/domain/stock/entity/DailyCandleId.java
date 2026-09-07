package com.finch.domain.stock.entity;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * {@link DailyCandle} 의 복합 키 (종목코드, 거래일). {@code @IdClass} 용이라 엔티티의 두 {@code @Id} 필드와 이름·타입이 같아야 한다.
 * <p>
 * 대리 키를 두지 않는 이유 — (종목, 날짜) 가 자연 키이고 PK 가 곧 {@code period} range scan 의 인덱스다 (erd.md §2.8).
 */
public record DailyCandleId(String stockCode, LocalDate tradeDate) implements Serializable {
}
