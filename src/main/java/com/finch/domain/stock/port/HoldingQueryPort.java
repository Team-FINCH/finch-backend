package com.finch.domain.stock.port;

import java.util.Optional;

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
	 * @param quantity    보유 수량. 항상 1 이상이다.
	 * @param avgBuyPrice 가중평균 매입 단가.
	 */
	record HoldingSnapshot(long quantity, long avgBuyPrice) {
	}
}
