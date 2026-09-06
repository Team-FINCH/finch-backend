package com.finch.domain.deposit.service;

import com.finch.domain.account.dto.response.AccountBalanceRes;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.deposit.DepositProperties;
import com.finch.domain.deposit.dto.response.DepositLimitRes;
import com.finch.domain.deposit.dto.response.DepositReadyRes;
import com.finch.domain.deposit.dto.response.MockApproveRes;
import com.finch.domain.deposit.entity.Payment;
import com.finch.domain.deposit.entity.PaymentMethod;
import com.finch.domain.deposit.entity.PaymentStatus;
import com.finch.domain.deposit.exception.DepositErrorCode;
import com.finch.domain.deposit.exception.DepositRejectedException;
import com.finch.domain.deposit.gateway.MockScenario;
import com.finch.domain.deposit.gateway.PaymentGateway;
import com.finch.domain.deposit.gateway.PaymentGatewayException;
import com.finch.domain.deposit.gateway.PaymentGatewayRouter;
import com.finch.domain.deposit.repository.DepositRepository;
import com.finch.domain.deposit.repository.PaymentRepository;
import com.finch.domain.ledger.service.LedgerService;
import com.finch.global.exception.CustomException;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 충전의 4단계 — 준비(ready) → 결제창 → 승인 → 확정(confirm) — 를 소유한다 (apiSpec §4, featureSpec 3.2).
 * <p>
 * <b>예수금이 늘어나는 곳은 {@code confirm} 하나다.</b> 나머지는 전부 {@code payment} 의 상태만 바꾼다.
 * 이 서비스는 카카오였는지 모의창이었는지 모른다 — {@link PaymentGatewayRouter} 뒤의 구현이 같은 모양으로 답한다.
 * <p>
 * <b>트랜잭션 경계가 메서드마다 다르다.</b> 외부 HTTP(카카오)를 부르는 준비·카카오 승인은 클래스 수준
 * {@code @Transactional} 을 쓸 수 없다 — 카카오가 느린 동안 DB 커넥션과 행 락을 붙잡고 있으면 그 사용자의
 * 주문까지 함께 막힌다 (erd.md §3.3, backConvention 2.2). 그래서 그 둘은 {@link TransactionTemplate} 으로 짧은
 * 트랜잭션을 <b>외부 호출 앞뒤에 따로</b> 연다. 외부 호출이 없는 모의 승인·확정은 {@code @Transactional} 하나다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositService {

	private final PaymentRepository paymentRepository;
	private final DepositRepository depositRepository;
	private final PaymentGatewayRouter gatewayRouter;
	private final AccountService accountService;
	private final LedgerService ledgerService;
	private final DepositProperties properties;
	private final TransactionTemplate transactionTemplate;

	/** apiSpec 4.1. 한도의 기준은 계정 전체 누적이고 출금해도 줄지 않는다. */
	@Transactional(readOnly = true)
	public DepositLimitRes getLimit(Long userId) {
		AccountBalanceRes account = accountService.getBalance(userId);
		return DepositLimitRes.of(properties.perRequestLimit(), properties.cumulativeLimit(),
			account.totalDepositedAmount());
	}

	/**
	 * 결제 준비 (apiSpec 4.2). 판정 순서는 명세 표 그대로다 — 금액 0 이하 → 1회 한도 → 누적 한도 → PG.
	 * ({@code paymentMethod} 열거값 검사는 컨트롤러 앞 JSON 파싱이 한다.)
	 * <p>
	 * 누적 한도는 <b>여기서 미리 한 번, confirm 에서 다시 한 번</b> 본다. 여기 검사는 사용자 편의라 잠그지 않고
	 * 읽은 값으로 하고(결제창까지 갔다가 막히는 것보다 낫다), 진실은 confirm 의 잠근 값이다.
	 * <p>
	 * 트랜잭션이 셋으로 나뉜다 — (1) READY 행 INSERT (2) PG 호출은 밖에서 (3) 결과 반영. PG 호출 <b>전에</b>
	 * 행을 커밋하는 이유는 카카오 {@code partner_order_id} 로 우리 {@code paymentId} 를 보내야 해서다.
	 * PG 가 실패하면 그 행을 FAILED 로 굳히고 502 다 — 행을 지우지 않는다. 실패도 감사 대상이다.
	 */
	public DepositReadyRes ready(Long userId, PaymentMethod method, long amount) {
		if (amount <= 0) {
			throw new CustomException(DepositErrorCode.DEPOSIT_AMOUNT_INVALID);
		}
		if (amount > properties.perRequestLimit()) {
			throw new CustomException(DepositErrorCode.DEPOSIT_PER_REQUEST_LIMIT_EXCEEDED);
		}
		AccountBalanceRes account = accountService.getBalance(userId);
		long remaining = properties.cumulativeLimit() - account.totalDepositedAmount();
		if (amount > remaining) {
			throw new CustomException(DepositErrorCode.DEPOSIT_LIMIT_EXCEEDED, Map.of("remainingAmount", remaining));
		}

		Instant expiresAt = Instant.now().plus(properties.readyTtl());
		Payment payment = transactionTemplate.execute(status ->
			paymentRepository.save(Payment.ready(account.accountId(), method, amount, expiresAt)));

		PaymentGateway.ReadyResult result;
		try {
			result = gatewayRouter.route(method).ready(payment);
		} catch (PaymentGatewayException e) {
			markFailed(payment.getId(), e.getFailCode());
			throw new CustomException(DepositErrorCode.DEPOSIT_PG_UNAVAILABLE);
		}

		if (result.pgTid() != null) {
			transactionTemplate.executeWithoutResult(status ->
				paymentRepository.findById(payment.getId()).orElseThrow().attachPgTid(result.pgTid()));
		}
		log.info("결제 준비 paymentId={} method={} amount={} live={}", payment.getId(), method, amount,
			gatewayRouter.isKakaoPayLive());
		return DepositReadyRes.of(payment.getId(), method, amount, result.checkoutUrl(), expiresAt);
	}

	/**
	 * 카카오 승인 콜백 (apiSpec 4.3.1). <b>무인증</b>이고 응답은 언제나 302 다 — 사용자의 브라우저가 카카오에서
	 * 돌아오는 요청이라 토큰이 없고, 본문 에러를 보여줄 화면도 없다. 실패 사유는 프론트 실패 URL 의 {@code code} 로 간다.
	 * <p>
	 * <b>무인증인데 안전한 이유</b> — 여기서 하는 일은 건을 APPROVED 로 표시하는 것뿐이고 <b>돈은 움직이지 않는다.</b>
	 * 위조하려면 카카오가 발급한 {@code pg_token} 이 필요한데 그 검증은 카카오 approve 가 한다. 남의 {@code paymentId}
	 * 를 넣어도 404 를 주지 않는다 — 그것 자체가 "그 번호는 존재한다"는 정보라 실패 URL 로 똑같이 보낸다.
	 * <p>
	 * 카카오 approve 는 트랜잭션 밖에서 부르고, 결과만 들고 짧은 트랜잭션에서 잠근 뒤 상태를 바꾼다. 잠근 뒤 다시
	 * READY 인지 보는 이유 — 같은 콜백이 두 번 도착(뒤로가기·새로고침)해도 두 번째는 카카오가 거절하지만, 그 사이
	 * 만료 배치가 FAILED 로 바꿨을 수 있다. FAILED·DONE 에서 APPROVED 로 가는 전이는 없다.
	 *
	 * @return 사용자를 보낼 프론트 URL.
	 */
	public String kakaoApproval(Long paymentId, String pgToken) {
		Payment payment = paymentRepository.findById(paymentId).orElse(null);
		if (payment == null) {
			return failRedirect(paymentId, DepositErrorCode.DEPOSIT_NOT_FOUND);
		}
		if (payment.getPaymentMethod() != PaymentMethod.KAKAOPAY || payment.getStatus() != PaymentStatus.READY) {
			return failRedirect(paymentId, DepositErrorCode.DEPOSIT_INVALID_STATE);
		}
		if (pgToken == null || pgToken.isBlank()) {
			return failRedirect(paymentId, DepositErrorCode.DEPOSIT_PAYMENT_FAILED);
		}

		PaymentGateway.ApproveResult approved;
		try {
			approved = gatewayRouter.route(PaymentMethod.KAKAOPAY).approve(payment, pgToken);
		} catch (PaymentGatewayException e) {
			markFailed(paymentId, e.getFailCode());
			return failRedirect(paymentId, e.getKind() == PaymentGatewayException.Kind.DECLINED
				? DepositErrorCode.DEPOSIT_PAYMENT_FAILED : DepositErrorCode.DEPOSIT_PG_UNAVAILABLE);
		}
		if (approved.approvedAmount() != payment.getAmount()) {
			// 카카오가 승인한 금액이 우리가 준비한 금액과 다르다. 결제창에서 금액이 바뀔 경로는 없어야 하므로 위변조로 본다.
			markFailed(paymentId, "AMOUNT_MISMATCH");
			return failRedirect(paymentId, DepositErrorCode.DEPOSIT_AMOUNT_MISMATCH);
		}

		Boolean recorded = transactionTemplate.execute(status -> {
			Payment locked = paymentRepository.findByIdForUpdate(paymentId).orElseThrow();
			if (locked.getStatus() != PaymentStatus.READY) {
				return false;
			}
			locked.approve(approved.paymentKey(), Instant.now());
			return true;
		});
		if (!Boolean.TRUE.equals(recorded)) {
			return failRedirect(paymentId, DepositErrorCode.DEPOSIT_INVALID_STATE);
		}
		log.info("카카오 승인 paymentId={} amount={}", paymentId, payment.getAmount());
		return successRedirect(paymentId, approved.paymentKey(), payment.getAmount());
	}

	/**
	 * 모의 이체 승인 (apiSpec 4.3.2). JWT 필수이고 <b>본인 건만</b> 승인한다. 판정 순서는 11.2 표 — 내 건인지 →
	 * TRANSFER 인지 → 시나리오. 실패 시나리오면 그 건은 FAILED 로 굳고 409 다.
	 * <p>
	 * 외부 호출이 없어 트랜잭션 하나다. {@code noRollbackFor} 인 이유는 {@link DepositRejectedException} 주석에 있다 —
	 * 실패 시나리오의 FAILED 가 커밋되어야 같은 건을 다시 승인할 수 없다.
	 */
	@Transactional(noRollbackFor = DepositRejectedException.class)
	public MockApproveRes mockApprove(Long userId, Long paymentId, MockScenario scenario) {
		AccountBalanceRes account = accountService.getBalance(userId);
		Payment payment = paymentRepository.findByIdForUpdate(paymentId)
			.filter(p -> p.getAccountId().equals(account.accountId()))
			.orElseThrow(() -> new CustomException(DepositErrorCode.DEPOSIT_NOT_FOUND));
		if (payment.getPaymentMethod() != PaymentMethod.TRANSFER || payment.getStatus() != PaymentStatus.READY) {
			throw new CustomException(DepositErrorCode.DEPOSIT_INVALID_STATE);
		}

		PaymentGateway.ApproveResult approved;
		try {
			approved = gatewayRouter.route(PaymentMethod.TRANSFER).approve(payment, scenario.name());
		} catch (PaymentGatewayException e) {
			payment.fail(e.getFailCode());
			throw new DepositRejectedException(DepositErrorCode.DEPOSIT_PAYMENT_FAILED);
		}
		payment.approve(approved.paymentKey(), Instant.now());
		return new MockApproveRes(payment.getId(), approved.paymentKey(), payment.getAmount());
	}

	private String successRedirect(Long paymentId, String paymentKey, long amount) {
		return UriComponentsBuilder.fromUriString(properties.successUrl())
			.queryParam("paymentId", paymentId)
			.queryParam("paymentKey", paymentKey)
			.queryParam("amount", amount)
			.build()
			.toUriString();
	}

	private String failRedirect(Long paymentId, DepositErrorCode code) {
		return UriComponentsBuilder.fromUriString(properties.failUrl())
			.queryParam("paymentId", paymentId)
			.queryParam("code", code.getCode())
			.build()
			.toUriString();
	}

	/** 외부 호출이 실패한 건을 짧은 트랜잭션으로 FAILED 로 굳힌다. 호출자의 트랜잭션이 없는 자리에서만 쓴다. */
	private void markFailed(Long paymentId, String failCode) {
		transactionTemplate.executeWithoutResult(status ->
			paymentRepository.findById(paymentId).ifPresent(payment -> payment.fail(failCode)));
	}
}
