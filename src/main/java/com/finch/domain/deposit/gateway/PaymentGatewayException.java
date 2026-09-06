package com.finch.domain.deposit.gateway;

import lombok.Getter;

/**
 * PG 호출이 실패했다. 사용자 에러 코드로 바꾸는 것은 {@code DepositService} 의 몫이다 — 같은 실패라도
 * 결제 준비에서는 502, 모의 승인에서는 409, 카카오 콜백에서는 302 실패 URL 로 답이 달라서다.
 */
@Getter
public class PaymentGatewayException extends RuntimeException {

	/**
	 * 실패의 종류. 사용자에게 보여줄 코드가 갈린다.
	 * <ul>
	 *   <li>{@code DECLINED} — 결제 자체가 거절됐다 (잔액 부족, 인증 시간 초과). {@code DEPOSIT_PAYMENT_FAILED}.</li>
	 *   <li>{@code UNAVAILABLE} — PG 와 통신이 안 되거나 PG 가 오류를 냈다. {@code DEPOSIT_PG_UNAVAILABLE}.</li>
	 * </ul>
	 */
	public enum Kind {
		DECLINED, UNAVAILABLE
	}

	private final Kind kind;

	/** {@code payment.fail_code} 에 남길 값. 50자 제한이라 생성자에서 자른다. */
	private final String failCode;

	public PaymentGatewayException(Kind kind, String failCode, String message) {
		super(message);
		this.kind = kind;
		this.failCode = failCode.length() > 50 ? failCode.substring(0, 50) : failCode;
	}
}
