package com.finch.domain.stock.exception;

import com.finch.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * apiSpec 11장 "종목" 목록.
 * 관심 종목·주문처럼 stockCode 를 받는 다른 도메인도 종목이 없으면 이 코드를 그대로 쓴다 —
 * 같은 상황에 도메인마다 다른 코드를 만들지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum StockErrorCode implements BaseErrorCode {

	STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다");

	private final HttpStatus status;
	private final String message;

	/** 코드 문자열은 enum 이름이다. 이유는 GeneralErrorCode 참고. */
	@Override
	public String getCode() {
		return name();
	}
}
