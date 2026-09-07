package com.finch.domain.withdrawal.dto.response;

import com.finch.global.util.KstTime;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * `POST /withdrawals` 응답 (apiSpec 4.5).
 * <p>
 * 같은 {@code Idempotency-Key} 로 다시 오면 이 본문이 <b>필터의 저장본</b>으로 그대로 재생된다 (apiSpec 1.4).
 * 충전 confirm 과 달리 서비스가 재생 경로를 갖지 않는다 — 재생은 {@code global/idempotency} 의 몫이다.
 *
 * @param cashBalanceAfter 출금 직후 예수금. 원장 행의 {@code cash_balance_after} 와 같은 값이다.
 * @param withdrawnAt      원장 행의 {@code occurred_at}. KST 오프셋 표기다 (apiSpec 1.1).
 */
public record WithdrawalRes(Long withdrawalId, long amount, long cashBalanceAfter, OffsetDateTime withdrawnAt) {

	public static WithdrawalRes of(Long withdrawalId, long amount, long cashBalanceAfter, Instant withdrawnAt) {
		return new WithdrawalRes(withdrawalId, amount, cashBalanceAfter, KstTime.toResponse(withdrawnAt));
	}
}
