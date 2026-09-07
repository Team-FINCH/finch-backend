package com.finch.domain.order.exception;

import com.finch.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * apiSpec 11장 "주문" 목록. 판정 순서는 apiSpec 7.2 체결 처리 순서를 따른다.
 * <p>
 * GET /orders/available 의 reason 에도 이 코드 문자열이 담긴다 (apiSpec 7.3) —
 * 그쪽은 HTTP 200 이고 에러가 아니지만 같은 문자열을 써서 화면이 하나의 분기 표를 쓰게 한다.
 */
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements BaseErrorCode {

	ORDER_QUANTITY_INVALID(HttpStatus.BAD_REQUEST, "주문 수량은 1주 이상이어야 합니다"),
	ORDER_MARKET_CLOSED(HttpStatus.CONFLICT, "지금은 주문할 수 없어요 (거래 시간 09:00~15:30)"),
	/** detail 에 거래정지 사유를 싣는다 (apiSpec 7.2). */
	ORDER_STOCK_SUSPENDED(HttpStatus.CONFLICT, "거래정지 종목은 주문할 수 없어요"),
	/**
	 * detail 에 {required, available} 을 싣는다 (apiSpec 1.3 예시). 체결 직전 재검증(7.2 4단계)에서 예수금이 부족하면
	 * <b>전부</b> 이 코드다 — "가격이 변동돼 부족해진 것"을 가르던 {@code ORDER_PRICE_CHANGED} 는 v0.8 에서 폐기됐다.
	 * 요청 본문에 확인 화면의 기준가가 없어 서버가 둘을 구분할 수 없고, 사용자가 할 일도 같다 (apiSpec §13, 이슈 #33).
	 */
	ORDER_INSUFFICIENT_CASH(HttpStatus.CONFLICT, "예수금이 부족합니다"),
	ORDER_INSUFFICIENT_QUANTITY(HttpStatus.CONFLICT, "보유 수량이 부족합니다"),
	ORDER_PRICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "시세를 불러올 수 없어 주문이 제한됩니다");

	private final HttpStatus status;
	private final String message;

	/** 코드 문자열은 enum 이름이다. 이유는 GeneralErrorCode 참고. */
	@Override
	public String getCode() {
		return name();
	}
}
