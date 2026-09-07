package com.finch.domain.stock.port;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * 보유 수량·평단을 묻는 창구. <b>{@code stock}(1층)이 선언하고 {@code portfolio}(3층, S8)가 구현한다.</b>
 * <p>
 * 종목 상세의 {@code holding} 블록(apiSpec 5.2)에 쓴다. 이유는 {@link PriceQueryPort} 와 같다 — 아래층이 위층을 부를 수 없다.
 * <p>
 * <b>{@code quantity = 0} 인 행은 empty 다.</b> 전량 매도 뒤 남는 0 수량 행은 재매수 INSERT 경합을 막는 DB 내부 구현이고
 * (erd.md §2.6) API 로는 {@code holding: null} 이어야 한다 (apiSpec 5.2, contracts C76). 그 판정을 구현체 재량에 두지 않고
 * <b>포트 계약</b>으로 못 박는다 — 구현체가 0 을 돌려주면 계약 위반이다.
 */
public interface HoldingQueryPort {

	Optional<HoldingSnapshot> holdingOf(Long userId, String stockCode);

	/**
	 * 이 중 어느 종목을 보유 중인가. 관심 종목 목록의 {@code held} 뱃지가 쓴다 (apiSpec 6.3).
	 * <p>
	 * 낱개 조회를 반복하지 않는 이유 — 관심 종목은 최대 50개다. {@link #holdingOf} 를 50번 부르면 S8 이 구현을 붙이는 순간 N+1 이 된다.
	 * <b>{@code quantity = 0} 인 종목은 결과에 넣지 않는다</b> — {@link #holdingOf} 와 같은 계약이다.
	 *
	 * @return 보유 중인 종목코드만. 인자에 없던 코드는 담지 않는다.
	 */
	Set<String> heldCodesAmong(Long userId, Collection<String> stockCodes);

	/**
	 * @param quantity    보유 수량. 항상 1 이상이다.
	 * @param avgBuyPrice 가중평균 매입 단가.
	 */
	record HoldingSnapshot(long quantity, long avgBuyPrice) {
	}
}
