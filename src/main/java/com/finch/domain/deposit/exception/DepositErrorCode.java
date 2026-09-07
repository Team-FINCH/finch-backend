package com.finch.domain.deposit.exception;

import com.finch.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * apiSpec 11장 "충전" 목록. 한도 수치는 apiSpec 4.2 (1회 1,000만 원 · 계정 누적 1억 원).
 * <p>
 * 어느 코드가 어느 엔드포인트에서 나가는지는 apiSpec 11.2 표가 정한다. 특히 confirm 은
 * {@code DEPOSIT_PG_UNAVAILABLE} 을 내지 않는다 — 확정 단계는 PG 를 부르지 않는다 ({@code PaymentGateway} 주석).
 */
@Getter
@RequiredArgsConstructor
public enum DepositErrorCode implements BaseErrorCode {

	DEPOSIT_AMOUNT_INVALID(HttpStatus.BAD_REQUEST, "충전 금액은 1원 이상이어야 합니다"),
	DEPOSIT_PER_REQUEST_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "1회 충전 한도는 1,000만 원입니다"),
	/** detail 에 {remainingAmount} 를 싣는다 (apiSpec 4.2). confirm 에서 나면 그 건은 FAILED 로 굳는다 (4.4). */
	DEPOSIT_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "누적 충전 한도(1억 원)를 초과했습니다"),
	/** confirm 이 보낸 금액이 준비 시점과 다르다. 위변조로 보고 그 건은 FAILED 로 굳는다 (apiSpec 4.4 판정 4). */
	DEPOSIT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 준비된 금액과 다릅니다. 처음부터 다시 진행해 주세요"),
	/** 없는 paymentId·paymentKey 이거나 내 것이 아니다. 둘을 가르지 않는다 — 남의 건의 존재를 알리지 않기 위해서다. */
	DEPOSIT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 정보를 찾을 수 없습니다"),
	/** 아직 승인 전(READY)인 건을 확정하려 했다. */
	DEPOSIT_NOT_APPROVED(HttpStatus.CONFLICT, "아직 결제가 완료되지 않았습니다"),
	/** 실패로 굳은 건(FAILED). 다시 확정할 수 없고 처음부터 해야 한다 (featureSpec 3.3). */
	DEPOSIT_PAYMENT_FAILED(HttpStatus.CONFLICT, "결제가 실패했습니다. 처음부터 다시 진행해 주세요"),
	/** 그 상태·수단에서 허용되지 않는 호출. 예: KAKAOPAY 건에 mock-approve. */
	DEPOSIT_INVALID_STATE(HttpStatus.CONFLICT, "지금 상태에서는 처리할 수 없는 결제입니다"),
	/** PG 가 결제 준비를 거절했거나 응답이 없다 (apiSpec 4.2 판정 5). */
	DEPOSIT_PG_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "결제를 시작할 수 없습니다. 잠시 후 다시 시도해 주세요");

	private final HttpStatus status;
	private final String message;

	/** 코드 문자열은 enum 이름이다. 이유는 GeneralErrorCode 참고. */
	@Override
	public String getCode() {
		return name();
	}
}
