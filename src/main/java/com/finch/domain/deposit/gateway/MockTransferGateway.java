package com.finch.domain.deposit.gateway;

import com.finch.domain.deposit.DepositProperties;
import com.finch.domain.deposit.entity.Payment;
import com.finch.domain.deposit.entity.PaymentMethod;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 자체 모의 PG. 계좌이체({@code TRANSFER})는 언제나 여기이고, 카카오 키가 없는 환경
 * ({@code finch.deposit.kakaopay.enabled=false})에서는 {@code KAKAOPAY} 도 여기가 맡는다.
 * <p>
 * 외부 호출이 없다. "결제창"은 프론트의 모의 이체 화면이고, "승인"은 시나리오 값을 보고 성공·실패를 정하는
 * 것이 전부다. 그래도 {@link PaymentGateway} 뒤에 서는 이유 — {@code DepositService} 가 수단을 모르게 하려면
 * 모의창도 진짜 PG 와 같은 모양으로 답해야 한다. 특히 {@code paymentKey} 를 <b>PG 가 발급한 값</b>으로 다루는
 * 규칙(apiSpec §1.4)이 모의창에서도 지켜져야 confirm 코드가 하나로 유지된다.
 */
@Component
@RequiredArgsConstructor
public class MockTransferGateway implements PaymentGateway {

	/** 키 모양. 카카오의 aid 와 구분되게 접두사를 붙인다. 8 + 32 = 40자로 {@code VARCHAR(64)} 안이다. */
	static final String KEY_PREFIX = "mock_pk_";

	private final DepositProperties properties;

	/** 프론트의 모의 이체 화면으로 보낸다. 카카오 대행 모드에서도 같은 화면이다 — 키 없는 환경은 시연이 아니라 테스트다. */
	@Override
	public ReadyResult ready(Payment payment) {
		String checkoutUrl = UriComponentsBuilder.fromUriString(properties.transferCheckoutUrl())
			.queryParam("paymentId", payment.getId())
			.build()
			.toUriString();
		return new ReadyResult(checkoutUrl, null);
	}

	/**
	 * 승인. {@code TRANSFER} 의 토큰은 시나리오 이름이고, 실패 시나리오면 그 이름을 실패 코드로 던진다.
	 * 카카오 대행 모드({@code KAKAOPAY})의 토큰은 {@code pg_token} 자리인데 검증할 카카오가 없으므로
	 * 값이 있기만 하면 승인한다.
	 */
	@Override
	public ApproveResult approve(Payment payment, String token) {
		if (payment.getPaymentMethod() == PaymentMethod.TRANSFER) {
			MockScenario scenario = scenarioOf(token);
			if (scenario.isFailure()) {
				throw new PaymentGatewayException(PaymentGatewayException.Kind.DECLINED, scenario.name(),
					"모의 이체 실패 시나리오: " + scenario);
			}
		} else if (token == null || token.isBlank()) {
			throw new PaymentGatewayException(PaymentGatewayException.Kind.DECLINED, "PG_TOKEN_MISSING",
				"승인 토큰이 없다");
		}
		return new ApproveResult(KEY_PREFIX + UUID.randomUUID().toString().replace("-", ""), payment.getAmount());
	}

	private static MockScenario scenarioOf(String token) {
		if (token == null || token.isBlank()) {
			return MockScenario.SUCCESS;
		}
		return MockScenario.valueOf(token);
	}
}
