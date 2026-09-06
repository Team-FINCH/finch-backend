package com.finch.domain.deposit.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * `POST /deposits/confirm` 요청 (apiSpec 4.4). 셋 다 승인 단계가 돌려준 값을 그대로 되돌려 보내는 것이다.
 * {@code amount} 는 서버가 이미 아는 값이지만 다시 받는다 — 준비 시점과 다르면 위변조로 보고 그 건을 굳힌다 (판정 4).
 */
public record DepositConfirmReq(
	@NotNull(message = "필수 값입니다") Long paymentId,
	@NotBlank(message = "필수 값입니다") String paymentKey,
	@NotNull(message = "필수 값입니다") Long amount
) {
}
