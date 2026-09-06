package com.finch.domain.deposit.gateway;

/**
 * 모의 이체 승인의 결과를 고르는 값 (apiSpec 4.3.2). 실패 흐름을 시연·테스트하기 위한 것이고 기본은 SUCCESS 다.
 * 이름이 그대로 {@code payment.fail_code} 가 된다.
 */
public enum MockScenario {

	SUCCESS,

	/** 잔액 부족 */
	INSUFFICIENT_BALANCE,

	/** 이체 한도 초과 */
	LIMIT_EXCEEDED,

	/** 인증 시간 초과 */
	TIMEOUT;

	public boolean isFailure() {
		return this != SUCCESS;
	}
}
