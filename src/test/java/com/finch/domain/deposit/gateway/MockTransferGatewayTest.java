package com.finch.domain.deposit.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.domain.deposit.DepositProperties;
import com.finch.domain.deposit.entity.Payment;
import com.finch.domain.deposit.entity.PaymentMethod;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 모의 PG 가 진짜 PG 와 같은 모양으로 답하는지 본다 — {@code DepositService} 가 수단을 모를 수 있는 조건이다. */
class MockTransferGatewayTest {

	private final MockTransferGateway gateway = new MockTransferGateway(properties());

	@Test
	@DisplayName("ready 는 프론트의 모의 이체 화면 주소에 paymentId 를 붙여 준다")
	void readyPointsToFrontTransferPage() {
		PaymentGateway.ReadyResult result = gateway.ready(payment(77L, PaymentMethod.TRANSFER));

		assertThat(result.checkoutUrl()).isEqualTo("http://localhost:5173/deposit/transfer?paymentId=77");
		assertThat(result.pgTid()).isNull();
	}

	@Test
	@DisplayName("시나리오 없이 승인하면 성공이고 키는 mock_pk_ 로 시작하는 40자다")
	void approveDefaultsToSuccess() {
		PaymentGateway.ApproveResult result = gateway.approve(payment(77L, PaymentMethod.TRANSFER), null);

		assertThat(result.paymentKey()).startsWith("mock_pk_").hasSize(40);
		assertThat(result.approvedAmount()).isEqualTo(1_000_000L);
	}

	@Test
	@DisplayName("승인마다 다른 키를 발급한다 — 키는 payment_key UNIQUE 의 대상이다")
	void issuesDistinctKeys() {
		String first = gateway.approve(payment(1L, PaymentMethod.TRANSFER), "SUCCESS").paymentKey();
		String second = gateway.approve(payment(2L, PaymentMethod.TRANSFER), "SUCCESS").paymentKey();

		assertThat(first).isNotEqualTo(second);
	}

	@Test
	@DisplayName("실패 시나리오는 DECLINED 이고 시나리오 이름이 fail_code 다")
	void failureScenarioIsDeclined() {
		assertThatThrownBy(() -> gateway.approve(payment(77L, PaymentMethod.TRANSFER), "INSUFFICIENT_BALANCE"))
			.isInstanceOf(PaymentGatewayException.class)
			.satisfies(e -> {
				PaymentGatewayException ex = (PaymentGatewayException) e;
				assertThat(ex.getKind()).isEqualTo(PaymentGatewayException.Kind.DECLINED);
				assertThat(ex.getFailCode()).isEqualTo("INSUFFICIENT_BALANCE");
			});
	}

	/** 키 없는 환경에서 KAKAOPAY 를 대행할 때다. 검증할 카카오가 없으므로 토큰이 있기만 하면 승인한다. */
	@Test
	@DisplayName("카카오 대행 모드에서는 pg_token 이 있으면 승인하고 없으면 거절한다")
	void kakaoStandInApprovesWithToken() {
		assertThat(gateway.approve(payment(77L, PaymentMethod.KAKAOPAY), "pg-token").paymentKey())
			.startsWith("mock_pk_");

		assertThatThrownBy(() -> gateway.approve(payment(77L, PaymentMethod.KAKAOPAY), ""))
			.isInstanceOf(PaymentGatewayException.class)
			.extracting("failCode").isEqualTo("PG_TOKEN_MISSING");
	}

	private static DepositProperties properties() {
		return new DepositProperties(10_000_000L, 100_000_000L, Duration.ofMinutes(15),
			"http://localhost:8080", "http://localhost:5173", "/deposit/complete", "/deposit/fail", "/deposit/transfer",
			new DepositProperties.KakaoPay(false, "unused", "TC0ONETIME", "https://kakao.test", Duration.ofSeconds(5)));
	}

	private static Payment payment(long id, PaymentMethod method) {
		Payment payment = Payment.ready(3L, method, 1_000_000L, Instant.now().plusSeconds(900));
		ReflectionTestUtils.setField(payment, "id", id);
		return payment;
	}
}
