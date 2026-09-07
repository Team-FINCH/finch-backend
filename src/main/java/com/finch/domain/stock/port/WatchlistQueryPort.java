package com.finch.domain.stock.port;

/**
 * 관심 종목 등록 여부를 묻는 창구. <b>{@code stock}(1층)이 선언하고 {@code watchlist}(4층, S6)가 구현한다.</b>
 * 종목 상세의 {@code watched}(토글 초기 상태, apiSpec 5.2)에 쓴다. 이유는 {@link PriceQueryPort} 와 같다.
 */
public interface WatchlistQueryPort {

	boolean isWatched(Long userId, String stockCode);
}
