package com.finch.domain.order.entity;

/**
 * 주문 방향 (apiSpec 7.1 {@code side}). 값 이름은 DB 의 {@code ck_trade_side} CHECK 목록과 문자 그대로 같아야 한다.
 * <p>
 * {@code LedgerType} 의 {@code BUY}·{@code SELL} 과 이름이 겹치지만 <b>합치지 않는다.</b> 원장 유형은 5종이고 그중 둘만 주문이다 —
 * 요청 본문의 {@code side} 에 {@code DEPOSIT} 이 들어올 수 있는 타입이면 Bean Validation 이 막아 주는 것이 없다.
 * 축이 다르면 타입도 다르다 ({@code LedgerType} 주석의 "필터 enum 을 따로 두는" 이유와 같다).
 */
public enum OrderSide {

	BUY,
	SELL;

	public boolean isBuy() {
		return this == BUY;
	}
}
