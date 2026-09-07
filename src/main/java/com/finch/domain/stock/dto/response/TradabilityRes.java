package com.finch.domain.stock.dto.response;

/**
 * 주문(S9)이 종목을 검사할 때 받는 답 — apiSpec 7.2 의 2단계(종목 존재)·3단계(거래정지). 다른 도메인이 {@code Stock} 엔티티를
 * import 하지 않게 하는 DTO 다 (backConvention 2.4 규칙 3).
 *
 * @param exists 활성 종목인지. 상장폐지도 false — 주문할 수 없는 종목은 없는 종목과 같다.
 */
public record TradabilityRes(boolean exists, boolean suspended, String suspendedReason) {

	public static TradabilityRes missing() {
		return new TradabilityRes(false, false, null);
	}
}
