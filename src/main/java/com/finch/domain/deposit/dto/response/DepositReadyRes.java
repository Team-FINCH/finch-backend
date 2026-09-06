package com.finch.domain.deposit.dto.response;

import com.finch.domain.deposit.entity.PaymentMethod;
import com.finch.global.util.KstTime;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * `POST /deposits/ready` 응답 (apiSpec 4.2).
 *
 * @param checkoutUrl 사용자를 보낼 결제창. KAKAOPAY 는 카카오 결제창, TRANSFER 는 프론트의 모의 이체 화면이다.
 *                    프론트는 수단을 구분하지 않고 이 URL 로 보낸다.
 * @param expiresAt   이 시각이 지나면 그 건은 만료된다 (서버가 FAILED 로 정리한다).
 */
public record DepositReadyRes(Long paymentId, PaymentMethod paymentMethod, long amount, String checkoutUrl,
	OffsetDateTime expiresAt) {

	public static DepositReadyRes of(Long paymentId, PaymentMethod paymentMethod, long amount, String checkoutUrl,
		Instant expiresAt) {
		return new DepositReadyRes(paymentId, paymentMethod, amount, checkoutUrl, KstTime.toResponse(expiresAt));
	}
}
