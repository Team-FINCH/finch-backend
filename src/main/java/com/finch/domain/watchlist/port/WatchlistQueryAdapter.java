package com.finch.domain.watchlist.port;

import com.finch.domain.stock.port.WatchlistQueryPort;
import com.finch.domain.watchlist.repository.WatchlistItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link WatchlistQueryPort} 의 실제 구현. <b>S5 가 두었던 {@code EmptyWatchlistQueryPort} 를 이 MR 에서 지웠다</b> — 남겨 두면
 * 빈이 둘이 되어 기동이 실패한다. 그 실패가 조용히 언제나 false 를 쓰는 것보다 낫다는 것이 S5 의 결정이었다.
 * <p>
 * 포트는 stock(1층)이 선언하고 watchlist(4층)가 구현한다. 컴파일 의존은 watchlist → stock 한 방향이고, 종목 상세가 실행 시점에
 * 이 구현을 부른다 (의존성 역전). 이 MR 이 머지되면 {@code GET /stocks/{stockCode}} 의 {@code watched} 가 실제 값이 된다.
 */
@Component
@RequiredArgsConstructor
public class WatchlistQueryAdapter implements WatchlistQueryPort {

	private final WatchlistItemRepository watchlistItemRepository;

	@Override
	public boolean isWatched(Long userId, String stockCode) {
		return watchlistItemRepository.existsByUserIdAndStockCode(userId, stockCode);
	}
}
