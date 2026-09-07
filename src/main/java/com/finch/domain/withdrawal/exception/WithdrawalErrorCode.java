package com.finch.domain.withdrawal.exception;

import com.finch.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * apiSpec 11장 "출금" 목록. 판정 순서는 apiSpec 4.5 표 — 멱등성(필터) → 금액 0 이하 → 예수금 부족.
 * <p>
 * 둘뿐이다. 출금은 한도가 없고(가능액 = 예수금 전액) 외부 PG 도 없어서 충전처럼 상태·수단·한도 코드가 생기지 않는다.
 * {@code IDEMPOTENCY_*} 는 공통 계층(apiSpec 11.1)이라 여기 없다.
 */
@Getter
@RequiredArgsConstructor
public enum WithdrawalErrorCode implements BaseErrorCode {

	WITHDRAWAL_AMOUNT_INVALID(HttpStatus.BAD_REQUEST, "출금 금액은 1원 이상이어야 합니다"),
	/** detail 에 {availableAmount} 를 싣는다 (apiSpec 4.5, featureSpec 3.4 "출금 가능: N원"). */
	WITHDRAWAL_INSUFFICIENT_CASH(HttpStatus.CONFLICT, "출금 가능 금액을 초과했습니다");

	private final HttpStatus status;
	private final String message;

	/** 코드 문자열은 enum 이름이다. 이유는 GeneralErrorCode 참고. */
	@Override
	public String getCode() {
		return name();
	}
}
