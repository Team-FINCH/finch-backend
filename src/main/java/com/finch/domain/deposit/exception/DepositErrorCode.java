package com.finch.domain.deposit.exception;

import com.finch.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** apiSpec 11장 "충전" 목록. 한도 수치는 apiSpec 4.2 (1회 1,000만 원 · 계정 누적 1억 원). */
@Getter
@RequiredArgsConstructor
public enum DepositErrorCode implements BaseErrorCode {

	DEPOSIT_AMOUNT_INVALID(HttpStatus.BAD_REQUEST, "충전 금액은 1원 이상이어야 합니다"),
	DEPOSIT_PER_REQUEST_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "1회 충전 한도는 1,000만 원입니다"),
	/** detail 에 {remainingAmount} 를 싣는다 (apiSpec 4.2). */
	DEPOSIT_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "누적 충전 한도(1억 원)를 초과했습니다");

	private final HttpStatus status;
	private final String message;

	/** 코드 문자열은 enum 이름이다. 이유는 GeneralErrorCode 참고. */
	@Override
	public String getCode() {
		return name();
	}
}
