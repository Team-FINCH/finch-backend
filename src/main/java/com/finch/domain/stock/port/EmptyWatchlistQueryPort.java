package com.finch.domain.stock.port;

import org.springframework.stereotype.Component;

/**
 * {@link WatchlistQueryPort} 의 기본 구현. <b>watchlist 도메인(S6)이 생기기 전까지</b>만 쓰인다. 언제나 false 다.
 * ⚠️ <b>S6 이 이 파일을 지운다.</b> 이유는 {@code EmptyPriceQueryPort}.
 */
@Component
public class EmptyWatchlistQueryPort implements WatchlistQueryPort {

	@Override
	public boolean isWatched(Long userId, String stockCode) {
		return false;
	}
}
