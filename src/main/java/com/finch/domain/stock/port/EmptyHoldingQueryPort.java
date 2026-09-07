package com.finch.domain.stock.port;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@link HoldingQueryPort} 의 기본 구현. <b>portfolio 도메인(S8)이 생기기 전까지</b>만 쓰인다. 언제나 "보유 없음"이다.
 * <p>
 * ⚠️ <b>S8 이 이 파일을 지운다.</b> 자세한 이유는 {@code EmptyPriceQueryPort}·{@code EmptyValuationPort}.
 * 지금은 주문이 없어 보유가 생길 경로 자체가 없으므로 이 답이 틀리지 않는다. S9(주문) 앞에 S8 이 오는 순서가 그것을 보장한다.
 */
@Component
public class EmptyHoldingQueryPort implements HoldingQueryPort {

	@Override
	public Optional<HoldingSnapshot> holdingOf(Long userId, String stockCode) {
		return Optional.empty();
	}

	@Override
	public Set<String> heldCodesAmong(Long userId, Collection<String> stockCodes) {
		return Set.of();
	}
}
