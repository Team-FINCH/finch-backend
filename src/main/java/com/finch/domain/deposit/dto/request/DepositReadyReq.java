package com.finch.domain.deposit.dto.request;

import com.finch.domain.deposit.entity.PaymentMethod;
import jakarta.validation.constraints.NotNull;

/**
 * `POST /deposits/ready` 요청 (apiSpec 4.2).
 * <p>
 * {@code amount} 에 {@code @Positive} 를 붙이지 않는다. 0 이하는 Bean Validation 의 {@code INVALID_REQUEST} 가 아니라
 * {@code DEPOSIT_AMOUNT_INVALID} 여야 하고(판정 2), 그 판정은 서비스가 한다. 여기서는 <b>있는지</b>만 본다.
 * {@code paymentMethod} 가 열거값 밖이면 JSON 파싱 단계에서 {@code INVALID_REQUEST} 다 (판정 1).
 */
public record DepositReadyReq(
	@NotNull(message = "필수 값입니다") Long amount,
	@NotNull(message = "필수 값입니다") PaymentMethod paymentMethod
) {
}
