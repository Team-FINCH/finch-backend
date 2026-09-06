package com.finch.domain.deposit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.deposit.entity.Payment;
import com.finch.domain.deposit.entity.PaymentMethod;
import com.finch.domain.deposit.entity.PaymentStatus;
import com.finch.domain.deposit.exception.DepositErrorCode;
import com.finch.domain.deposit.gateway.PaymentGateway;
import com.finch.domain.deposit.gateway.PaymentGatewayException;
import com.finch.domain.deposit.gateway.PaymentGatewayRouter;
import com.finch.domain.deposit.repository.PaymentRepository;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * PG 가 실패했을 때 <b>결제 건이 어떻게 남는지</b> 본다. 실패는 사용자 에러로 끝나지만 행은 FAILED 로 굳어야 한다 —
 * 지우지 않는 이유는 실패도 감사 대상이고, READY 로 남으면 만료 배치까지 "진행 중"으로 보이기 때문이다.
 * <p>
 * 라우터를 목으로 바꿔 어느 수단이든 실패하게 만든다. 실제 카카오 응답을 흉내내는 것은 {@code KakaoPayGatewayTest} 다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DepositServiceGatewayFailureTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(920_000_000L);

	@Autowired
	private DepositService depositService;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private UserRepository userRepository;

	@MockitoBean
	private PaymentGatewayRouter gatewayRouter;

	private final PaymentGateway gateway = Mockito.mock(PaymentGateway.class);

	@BeforeEach
	void routeEverythingToMock() {
		given(gatewayRouter.route(any())).willReturn(gateway);
	}

	@Test
	@DisplayName("ready 에서 PG 가 거절하면 DEPOSIT_PG_UNAVAILABLE 이고 그 건은 FAILED 로 남는다 (지우지 않는다)")
	void pgFailureOnReadyFreezesPayment() {
		Long userId = newUserId();
		given(gateway.ready(any())).willThrow(
			new PaymentGatewayException(PaymentGatewayException.Kind.UNAVAILABLE, "KAKAOPAY_-999", "down"));

		assertThatThrownBy(() -> depositService.ready(userId, PaymentMethod.KAKAOPAY, 1_000_000L))
			.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_PG_UNAVAILABLE);

		Payment payment = paymentRepository.findAll().stream()
			.filter(p -> p.getFailCode() != null && p.getFailCode().equals("KAKAOPAY_-999"))
			.findFirst().orElseThrow();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
	}

	@Test
	@DisplayName("ready 가 tid 를 받으면 pg_tid 로 저장한다 — approve 때 되돌려 보내야 한다")
	void storesPgTid() {
		Long userId = newUserId();
		given(gateway.ready(any())).willReturn(new PaymentGateway.ReadyResult("https://pay/checkout", "T-1"));

		Long paymentId = depositService.ready(userId, PaymentMethod.KAKAOPAY, 1_000_000L).paymentId();

		assertThat(paymentRepository.findById(paymentId).orElseThrow().getPgTid()).isEqualTo("T-1");
	}

	@Test
	@DisplayName("카카오 승인이 PG 장애로 실패하면 실패 URL 의 code 는 DEPOSIT_PG_UNAVAILABLE 이고 건은 FAILED 다")
	void pgFailureOnApprovalFreezesPayment() {
		Long userId = newUserId();
		given(gateway.ready(any())).willReturn(new PaymentGateway.ReadyResult("https://pay/checkout", "T-1"));
		Long paymentId = depositService.ready(userId, PaymentMethod.KAKAOPAY, 1_000_000L).paymentId();
		given(gateway.approve(any(), anyString())).willThrow(
			new PaymentGatewayException(PaymentGatewayException.Kind.UNAVAILABLE, "KAKAOPAY_TIMEOUT", "slow"));

		String url = depositService.kakaoApproval(paymentId, "pg-token");

		assertThat(url).contains("code=DEPOSIT_PG_UNAVAILABLE");
		Payment payment = paymentRepository.findById(paymentId).orElseThrow();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getFailCode()).isEqualTo("KAKAOPAY_TIMEOUT");
	}

	@Test
	@DisplayName("카카오 승인이 거절(DECLINED)이면 code 는 DEPOSIT_PAYMENT_FAILED 다")
	void declinedApprovalRedirectsAsPaymentFailed() {
		Long userId = newUserId();
		given(gateway.ready(any())).willReturn(new PaymentGateway.ReadyResult("https://pay/checkout", "T-1"));
		Long paymentId = depositService.ready(userId, PaymentMethod.KAKAOPAY, 1_000_000L).paymentId();
		given(gateway.approve(any(), anyString())).willThrow(
			new PaymentGatewayException(PaymentGatewayException.Kind.DECLINED, "USER_CANCELED", "canceled"));

		assertThat(depositService.kakaoApproval(paymentId, "pg-token")).contains("code=DEPOSIT_PAYMENT_FAILED");
		assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
	}

	/** 결제창에서 금액이 바뀔 경로는 없어야 한다. PG 가 다른 금액을 승인했다면 우리 쪽 위변조 방어가 뚫린 것이다. */
	@Test
	@DisplayName("PG 가 승인한 금액이 준비 금액과 다르면 DEPOSIT_AMOUNT_MISMATCH 로 실패 URL 이고 건은 FAILED 다")
	void approvedAmountMismatchFreezesPayment() {
		Long userId = newUserId();
		given(gateway.ready(any())).willReturn(new PaymentGateway.ReadyResult("https://pay/checkout", "T-1"));
		Long paymentId = depositService.ready(userId, PaymentMethod.KAKAOPAY, 1_000_000L).paymentId();
		given(gateway.approve(any(), anyString())).willReturn(new PaymentGateway.ApproveResult("A-1", 999_000L));

		assertThat(depositService.kakaoApproval(paymentId, "pg-token")).contains("code=DEPOSIT_AMOUNT_MISMATCH");
		Payment payment = paymentRepository.findById(paymentId).orElseThrow();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(payment.getFailCode()).isEqualTo("AMOUNT_MISMATCH");
		assertThat(payment.getPaymentKey()).isNull();
	}

	private Long newUserId() {
		User user = userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg"));
		accountService.ensureAccount(user.getId());
		return user.getId();
	}
}
