package com.finch.domain.deposit.dto.response;

/**
 * `GET /deposits/limit` 응답 (apiSpec 4.1). 화면은 한도를 계산하지 않고 이 값을 그대로 쓴다 (프론트 ia.md).
 *
 * @param depositedAmount 계좌 평생 누적 충전액. <b>출금해도 줄지 않는다</b> (apiSpec 4.5).
 * @param remainingAmount {@code cumulativeLimit - depositedAmount}.
 */
public record DepositLimitRes(long perRequestLimit, long cumulativeLimit, long depositedAmount, long remainingAmount) {

	public static DepositLimitRes of(long perRequestLimit, long cumulativeLimit, long depositedAmount) {
		return new DepositLimitRes(perRequestLimit, cumulativeLimit, depositedAmount, cumulativeLimit - depositedAmount);
	}
}
