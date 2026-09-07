package com.finch.domain.deposit.gateway;

import com.finch.domain.deposit.entity.Payment;

/**
 * 결제 수단별 PG 호출의 경계다. <b>결제 서버를 나중에 분리하면 이 인터페이스가 잘리는 선</b>이다 (apiSpec §4).
 * <p>
 * {@code DepositService} 는 카카오였는지 모의창이었는지 모른다 — 두 구현이 같은 모양의 {@code paymentKey} 를
 * 돌려주고, 같은 종류의 예외를 던진다. 그래서 확정(confirm) 로직이 수단마다 갈라지지 않는다.
 * <p>
 * <b>{@code confirm} 이 없다.</b> 카카오페이 온라인 결제는 approve 가 최종 승인이고 그 뒤에 따로 확정하는 API 가
 * 없다. 확정 단계에서 PG 를 다시 부르면 (1) apiSpec 4.4 가 confirm 에 허용하지 않은 502 경로가 생기고
 * (2) 원장 트랜잭션 앞에 네트워크 왕복이 하나 더 들어간다. 확정의 대조는 approve 때 우리가 저장한
 * {@code paymentKey} 와 요청의 키를 맞추는 것으로 충분하다 — 키는 PG 가 발급했고 우리 DB 에만 있다.
 */
public interface PaymentGateway {

	/**
	 * 결제 준비. 사용자를 보낼 결제창 주소를 받는다.
	 *
	 * @throws PaymentGatewayException PG 가 거절하거나 응답이 없을 때. 호출자가 건을 FAILED 로 굳힌다.
	 */
	ReadyResult ready(Payment payment);

	/**
	 * 결제 승인. 사용자가 결제창을 마치고 돌아온 뒤 부른다.
	 *
	 * @param token 수단별 승인 토큰. 카카오는 콜백에 붙어 온 {@code pg_token}, 모의 이체는 시나리오 이름이다.
	 * @throws PaymentGatewayException 승인 거절({@code DECLINED}) 또는 PG 장애({@code UNAVAILABLE}).
	 */
	ApproveResult approve(Payment payment, String token);

	/**
	 * @param checkoutUrl 사용자를 보낼 결제창 주소. 프론트는 수단을 구분하지 않고 이 URL 로 보낸다.
	 * @param pgTid       PG 거래 식별자. 모의 이체는 null.
	 */
	record ReadyResult(String checkoutUrl, String pgTid) {
	}

	/**
	 * @param paymentKey     PG 가 발급한 키. 이 값이 confirm 의 멱등 기준이 된다.
	 * @param approvedAmount PG 가 승인한 금액. 준비 금액과 다르면 호출자가 건을 FAILED 로 굳힌다.
	 */
	record ApproveResult(String paymentKey, long approvedAmount) {
	}
}
