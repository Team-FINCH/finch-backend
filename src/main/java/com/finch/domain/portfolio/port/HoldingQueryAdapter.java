package com.finch.domain.portfolio.port;

import com.finch.domain.portfolio.repository.HoldingRepository;
import com.finch.domain.stock.port.HoldingQueryPort;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link HoldingQueryPort} 의 실제 구현. stock(1층)이 선언하고 portfolio(3층)가 구현한다.
 * <b>{@code EmptyHoldingQueryPort} 를 이 MR 에서 지웠다</b> — 마지막 {@code Empty*Port} 였고, 이제 포트에 빈 구현이 하나도
 * 남아 있지 않다. 이 MR 이 머지되면 종목 상세의 보유 카드와 관심 목록의 {@code held} 뱃지가 실제 값이 된다.
 * <p>
 * {@link ValuationAdapter} 와 나누어 둔 이유는 그쪽 주석에 있다 — 한 빈이 포트 둘을 구현하면 테스트의 목 교체가
 * 다른 포트를 깨뜨린다.
 */
@Component
@RequiredArgsConstructor
public class HoldingQueryAdapter implements HoldingQueryPort {

	private final HoldingRepository holdingRepository;

	/** 종목 상세의 보유 카드 (apiSpec 5.2). <b>{@code quantity = 0} 인 행은 empty 다</b> — 포트 계약이고 쿼리가 그것을 보장한다. */
	@Override
	@Transactional(readOnly = true)
	public Optional<HoldingSnapshot> holdingOf(Long userId, String stockCode) {
		return holdingRepository.findHeldByUser(userId, stockCode)
			.map(row -> new HoldingSnapshot(row.getQuantity(), row.getAvgBuyPrice()));
	}

	/** 관심 목록의 {@code held} 뱃지 (apiSpec 6.3). 빈 목록이면 쿼리를 내지 않는다 — {@code IN ()} 은 SQL 문법 오류다. */
	@Override
	@Transactional(readOnly = true)
	public Set<String> heldCodesAmong(Long userId, Collection<String> stockCodes) {
		if (stockCodes.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(holdingRepository.findHeldCodesAmongByUser(userId, List.copyOf(stockCodes)));
	}
}
