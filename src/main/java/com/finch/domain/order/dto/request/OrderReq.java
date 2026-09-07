package com.finch.domain.order.dto.request;

import com.finch.domain.order.entity.OrderSide;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * `POST /orders` 요청 (apiSpec 7.1). 가격 필드가 없다 — 시장가뿐이고 체결가는 서버의 최신 수신 가격이다 (contracts C43).
 * <p>
 * {@code quantity} 에 {@code @Positive} 를 붙이지 않는다. 0 이하는 {@code INVALID_REQUEST} 가 아니라 {@code ORDER_QUANTITY_INVALID}
 * 여야 하고(apiSpec 11.2), 그 판정은 {@code OrderValidator} 가 한다. 여기서는 <b>있는지</b>만 본다. {@code WithdrawalReq} 와 같다.
 * {@code side} 가 열거값 밖이면 본문 파싱 단계에서 {@code INVALID_REQUEST} 다 — 그것이 판정 순서의 첫 칸이다.
 */
public record OrderReq(
	@NotBlank(message = "필수 값입니다") String stockCode,
	@NotNull(message = "필수 값입니다") OrderSide side,
	@NotNull(message = "필수 값입니다") Long quantity
) {
}
