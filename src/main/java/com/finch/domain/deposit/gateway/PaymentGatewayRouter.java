package com.finch.domain.deposit.gateway;

import com.finch.domain.deposit.entity.PaymentMethod;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 결제 수단 → 구현체. {@code DepositService} 가 아는 게이트웨이 진입점은 이것 하나다.
 * <p>
 * {@code KakaoPayGateway} 는 {@code finch.deposit.kakaopay.enabled=false} 면 빈이 없다. 그때 {@code KAKAOPAY} 는
 * {@link MockTransferGateway} 로 간다 — 키 없는 환경(테스트·CI)에서도 준비·승인·확정 전 경로가 돌아야
 * 확정 트랜잭션의 테스트가 수단을 가리지 않는다. {@link ObjectProvider} 로 받는 이유가 그 "있을 수도 없을 수도"다.
 */
@Component
public class PaymentGatewayRouter {

	private final MockTransferGateway mockTransferGateway;
	private final PaymentGateway kakaoPayGateway;

	public PaymentGatewayRouter(MockTransferGateway mockTransferGateway,
		ObjectProvider<KakaoPayGateway> kakaoPayGateway) {
		this.mockTransferGateway = mockTransferGateway;
		KakaoPayGateway live = kakaoPayGateway.getIfAvailable();
		this.kakaoPayGateway = live != null ? live : mockTransferGateway;
	}

	public PaymentGateway route(PaymentMethod method) {
		return switch (method) {
			case KAKAOPAY -> kakaoPayGateway;
			case TRANSFER -> mockTransferGateway;
		};
	}

	/** 실제 카카오를 치는 모드인가. 시연 로그와 헬스 표시용이다. */
	public boolean isKakaoPayLive() {
		return kakaoPayGateway != mockTransferGateway;
	}
}
