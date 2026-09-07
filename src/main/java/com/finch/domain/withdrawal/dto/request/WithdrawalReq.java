package com.finch.domain.withdrawal.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * `POST /withdrawals` 요청 (apiSpec 4.5). 입력은 금액 하나다 — 출금 수단을 받지 않는다 (featureSpec 3.4).
 * <p>
 * {@code amount} 에 {@code @Positive} 를 붙이지 않는다. 0 이하는 Bean Validation 의 {@code INVALID_REQUEST} 가 아니라
 * {@code WITHDRAWAL_AMOUNT_INVALID} 여야 하고(판정 2), 그 판정은 서비스가 한다. 여기서는 <b>있는지</b>만 본다.
 * {@code DepositReadyReq} 와 같은 이유다.
 */
public record WithdrawalReq(
	@NotNull(message = "필수 값입니다") Long amount
) {
}
