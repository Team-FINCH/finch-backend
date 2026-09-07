package com.finch.domain.deposit.dto.response;

import com.finch.domain.deposit.entity.PaymentMethod;
import com.finch.global.util.KstTime;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * `POST /deposits/confirm` 응답 (apiSpec 4.4). v0.7 의 `POST /deposits` 응답과 같은 모양이다.
 * 같은 {@code paymentKey} 로 다시 오면 <b>이 본문이 그대로</b> 200 으로 나간다 — 값은 deposit 행과 원장 행에서 되찾는다.
 */
public record DepositConfirmRes(Long depositId, long amount, PaymentMethod paymentMethod, long cashBalanceAfter,
	OffsetDateTime depositedAt) {

	public static DepositConfirmRes of(Long depositId, long amount, PaymentMethod paymentMethod, long cashBalanceAfter,
		Instant depositedAt) {
		return new DepositConfirmRes(depositId, amount, paymentMethod, cashBalanceAfter, KstTime.toResponse(depositedAt));
	}
}
