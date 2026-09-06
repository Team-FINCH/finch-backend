package com.finch.domain.deposit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.entity.Account;
import com.finch.domain.account.repository.AccountRepository;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.deposit.DepositProperties;
import com.finch.domain.deposit.dto.response.DepositConfirmOutcome;
import com.finch.domain.deposit.dto.response.DepositLimitRes;
import com.finch.domain.deposit.dto.response.DepositReadyRes;
import com.finch.domain.deposit.dto.response.MockApproveRes;
import com.finch.domain.deposit.entity.Deposit;
import com.finch.domain.deposit.entity.Payment;
import com.finch.domain.deposit.entity.PaymentMethod;
import com.finch.domain.deposit.entity.PaymentStatus;
import com.finch.domain.deposit.exception.DepositErrorCode;
import com.finch.domain.deposit.exception.DepositRejectedException;
import com.finch.domain.deposit.gateway.MockScenario;
import com.finch.domain.deposit.repository.DepositRepository;
import com.finch.domain.deposit.repository.PaymentRepository;
import com.finch.domain.ledger.entity.LedgerEntry;
import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.repository.LedgerEntryRepository;
import com.finch.global.exception.CustomException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 충전 4단계가 실제 DB 에서 무엇을 남기는지 본다. 목으로는 볼 수 없는 것들이 대상이다 — FOR UPDATE 의 줄 세우기,
 * {@code noRollbackFor} 가 FAILED 를 커밋하는지, 불변식 1·2·6·7.
 * <p>
 * {@code finch.deposit.kakaopay.enabled=false}(테스트 설정) 라 KAKAOPAY 도 {@code MockTransferGateway} 가 맡는다.
 * PG 가 실패하는 경로는 {@code DepositServiceGatewayFailureTest} 가 라우터를 목으로 바꿔 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DepositServiceTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(910_000_000L);
	private static final long AMOUNT = 1_000_000L;

	@Autowired
	private DepositService depositService;

	@Autowired
	private PaymentExpiryScheduler expiryScheduler;

	@Autowired
	private DepositProperties properties;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private DepositRepository depositRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Nested
	@DisplayName("ready")
	class Ready {

		@Test
		@DisplayName("READY 행을 남기고 만료 시각은 ready-ttl 뒤다")
		void createsReadyPayment() {
			Long userId = newUserId();
			Instant before = Instant.now();

			DepositReadyRes res = depositService.ready(userId, PaymentMethod.TRANSFER, AMOUNT);

			Payment payment = paymentRepository.findById(res.paymentId()).orElseThrow();
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
			assertThat(payment.getAmount()).isEqualTo(AMOUNT);
			assertThat(payment.getPaymentKey()).isNull();
			assertThat(payment.getExpiresAt()).isBetween(before.plus(properties.readyTtl()),
				Instant.now().plus(properties.readyTtl()));
			assertThat(res.checkoutUrl()).contains("paymentId=" + res.paymentId());
			// apiSpec 1.1 — 시각은 KST 오프셋을 포함한다.
			assertThat(res.expiresAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
		}

		@Test
		@DisplayName("예수금은 늘지 않는다 — 준비는 돈을 움직이지 않는다")
		void doesNotTouchBalance() {
			Long userId = newUserId();

			depositService.ready(userId, PaymentMethod.KAKAOPAY, AMOUNT);

			assertThat(account(userId).getCashBalance()).isZero();
			assertThat(ledgerEntryRepository.findByAccountIdOrderByIdDesc(account(userId).getId())).isEmpty();
		}

		@Test
		@DisplayName("판정 2 — 0 이하는 DEPOSIT_AMOUNT_INVALID 이고 행을 남기지 않는다")
		void rejectsNonPositiveAmount() {
			Long userId = newUserId();

			assertThatThrownBy(() -> depositService.ready(userId, PaymentMethod.TRANSFER, 0L))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_AMOUNT_INVALID);
			assertThatThrownBy(() -> depositService.ready(userId, PaymentMethod.TRANSFER, -1L))
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_AMOUNT_INVALID);
		}

		/** 경계값 — 정확히 1,000만 원은 통과, 1원 더는 거절. */
		@Test
		@DisplayName("판정 3 — 1회 한도 초과는 DEPOSIT_PER_REQUEST_LIMIT_EXCEEDED, 한도 그 자체는 통과")
		void rejectsOverPerRequestLimit() {
			Long userId = newUserId();

			assertThatThrownBy(() -> depositService.ready(userId, PaymentMethod.TRANSFER, properties.perRequestLimit() + 1))
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_PER_REQUEST_LIMIT_EXCEEDED);

			assertThat(depositService.ready(userId, PaymentMethod.TRANSFER, properties.perRequestLimit()).paymentId())
				.isNotNull();
		}

		/**
		 * 판정 순서 — 1회 한도와 누적 한도에 <b>동시에</b> 걸리는 금액은 1회 한도 코드가 먼저다.
		 * 누적이 먼저 나가면 "잔여 한도" 안내가 뜨는데 그 금액은 어차피 1회에 못 넣는다.
		 */
		@Test
		@DisplayName("판정 4 — 누적 한도 초과는 DEPOSIT_LIMIT_EXCEEDED 이고 detail.remainingAmount 가 정확하다")
		void rejectsOverCumulativeLimit() {
			Long userId = newUserId();
			depositUpTo(userId, properties.cumulativeLimit() - 1);

			assertThatThrownBy(() -> depositService.ready(userId, PaymentMethod.TRANSFER, 2L))
				.isInstanceOf(CustomException.class)
				.satisfies(e -> {
					CustomException ex = (CustomException) e;
					assertThat(ex.getErrorCode()).isEqualTo(DepositErrorCode.DEPOSIT_LIMIT_EXCEEDED);
					assertThat(ex.getDetail()).hasFieldOrPropertyWithValue("remainingAmount", 1L);
				});

			// 남은 1원은 넣을 수 있다.
			assertThat(depositService.ready(userId, PaymentMethod.TRANSFER, 1L).paymentId()).isNotNull();
		}
	}

	@Nested
	@DisplayName("mockApprove")
	class MockApprove {

		@Test
		@DisplayName("READY → APPROVED 로 바뀌고 mock_pk_ 키가 발급된다. 예수금은 그대로다")
		void approvesWithoutMovingMoney() {
			Long userId = newUserId();
			Long paymentId = depositService.ready(userId, PaymentMethod.TRANSFER, AMOUNT).paymentId();

			MockApproveRes res = depositService.mockApprove(userId, paymentId, MockScenario.SUCCESS);

			Payment payment = paymentRepository.findById(paymentId).orElseThrow();
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
			assertThat(payment.getPaymentKey()).isEqualTo(res.paymentKey()).startsWith("mock_pk_");
			assertThat(payment.getApprovedAt()).isNotNull();
			assertThat(res.amount()).isEqualTo(AMOUNT);
			assertThat(account(userId).getCashBalance()).isZero();
		}

		@Test
		@DisplayName("남의 건은 DEPOSIT_NOT_FOUND — 존재 여부를 가르지 않는다")
		void rejectsOthersPayment() {
			Long owner = newUserId();
			Long other = newUserId();
			Long paymentId = depositService.ready(owner, PaymentMethod.TRANSFER, AMOUNT).paymentId();

			assertThatThrownBy(() -> depositService.mockApprove(other, paymentId, MockScenario.SUCCESS))
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_NOT_FOUND);
			assertThatThrownBy(() -> depositService.mockApprove(owner, 999_999_999L, MockScenario.SUCCESS))
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_NOT_FOUND);
		}

		@Test
		@DisplayName("KAKAOPAY 건은 DEPOSIT_INVALID_STATE — 이 API 는 TRANSFER 전용이다")
		void rejectsKakaoPayment() {
			Long userId = newUserId();
			Long paymentId = depositService.ready(userId, PaymentMethod.KAKAOPAY, AMOUNT).paymentId();

			assertThatThrownBy(() -> depositService.mockApprove(userId, paymentId, MockScenario.SUCCESS))
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_INVALID_STATE);
		}

		@Test
		@DisplayName("이미 승인된 건을 다시 승인하면 DEPOSIT_INVALID_STATE 이고 키는 바뀌지 않는다")
		void rejectsSecondApproval() {
			Long userId = newUserId();
			Long paymentId = depositService.ready(userId, PaymentMethod.TRANSFER, AMOUNT).paymentId();
			String key = depositService.mockApprove(userId, paymentId, MockScenario.SUCCESS).paymentKey();

			assertThatThrownBy(() -> depositService.mockApprove(userId, paymentId, MockScenario.SUCCESS))
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_INVALID_STATE);
			assertThat(paymentRepository.findById(paymentId).orElseThrow().getPaymentKey()).isEqualTo(key);
		}

		/** {@code noRollbackFor} 가 일하는 자리다. 예외가 났는데도 FAILED 가 커밋되어야 한다. */
		@Test
		@DisplayName("실패 시나리오는 DEPOSIT_PAYMENT_FAILED 이고 그 건은 FAILED 로 굳는다 — 다시 승인할 수 없다")
		void failureScenarioFreezesPayment() {
			Long userId = newUserId();
			Long paymentId = depositService.ready(userId, PaymentMethod.TRANSFER, AMOUNT).paymentId();

			assertThatThrownBy(() -> depositService.mockApprove(userId, paymentId, MockScenario.INSUFFICIENT_BALANCE))
				.isInstanceOf(DepositRejectedException.class)
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_PAYMENT_FAILED);

			Payment payment = paymentRepository.findById(paymentId).orElseThrow();
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
			assertThat(payment.getFailCode()).isEqualTo("INSUFFICIENT_BALANCE");

			assertThatThrownBy(() -> depositService.mockApprove(userId, paymentId, MockScenario.SUCCESS))
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_INVALID_STATE);
		}
	}

	@Nested
	@DisplayName("confirm")
	class Confirm {

		@Test
		@DisplayName("정상 — 원장·deposit·계좌 세 곳이 한 트랜잭션에서 갱신되고 payment 는 DONE 이다")
		void reflectsDeposit() {
			Long userId = newUserId();
			MockApproveRes approved = approve(userId, PaymentMethod.TRANSFER, AMOUNT);

			DepositConfirmOutcome outcome = depositService.confirm(userId, approved.paymentId(), approved.paymentKey(),
				AMOUNT);

			assertThat(outcome.replayed()).isFalse();
			assertThat(outcome.body().amount()).isEqualTo(AMOUNT);
			assertThat(outcome.body().paymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
			assertThat(outcome.body().cashBalanceAfter()).isEqualTo(AMOUNT);
			assertThat(outcome.body().depositedAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);

			Account account = account(userId);
			assertThat(account.getCashBalance()).isEqualTo(AMOUNT);
			assertThat(account.getTotalDepositedAmount()).isEqualTo(AMOUNT);

			List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.getId());
			assertThat(entries).hasSize(1);
			assertThat(entries.getFirst().getType()).isEqualTo(LedgerType.DEPOSIT);
			assertThat(entries.getFirst().getCashDelta()).isEqualTo(AMOUNT);
			assertThat(entries.getFirst().getCashBalanceAfter()).isEqualTo(AMOUNT);

			Deposit deposit = depositRepository.findById(outcome.body().depositId()).orElseThrow();
			assertThat(deposit.getLedgerEntryId()).isEqualTo(entries.getFirst().getId());
			assertThat(deposit.getPaymentId()).isEqualTo(approved.paymentId());
			assertThat(deposit.getCreatedAt()).isEqualTo(entries.getFirst().getOccurredAt());

			Payment payment = paymentRepository.findById(approved.paymentId()).orElseThrow();
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
			assertThat(payment.getCompletedAt()).isEqualTo(entries.getFirst().getOccurredAt());
		}

		/** erd.md §4 — 원장과 스냅샷이 갈라지면 여기서 잡힌다. 세 번 충전한 뒤 대조한다. */
		@Test
		@DisplayName("불변식 1·2·6·7 — 잔고 = 원장 합, 누적 = deposit 합, DEPOSIT 원장 1행 = deposit 1행 = payment 1행")
		void satisfiesInvariants() {
			Long userId = newUserId();
			for (long amount : new long[] {300_000L, 1_000_000L, 2_500_000L}) {
				MockApproveRes approved = approve(userId, PaymentMethod.TRANSFER, amount);
				depositService.confirm(userId, approved.paymentId(), approved.paymentKey(), amount);
			}

			Account account = account(userId);
			List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.getId());
			List<Deposit> deposits = depositRepository.findAll().stream()
				.filter(d -> d.getAccountId().equals(account.getId())).toList();

			// 1
			assertThat(account.getCashBalance()).isEqualTo(entries.stream().mapToLong(LedgerEntry::getCashDelta).sum());
			// 2
			assertThat(account.getTotalDepositedAmount()).isEqualTo(depositRepository.sumAmountByAccountId(account.getId()))
				.isEqualTo(3_800_000L);
			// 6
			assertThat(entries).allMatch(e -> e.getType() == LedgerType.DEPOSIT).hasSize(deposits.size());
			assertThat(deposits).extracting(Deposit::getLedgerEntryId)
				.containsExactlyInAnyOrderElementsOf(entries.stream().map(LedgerEntry::getId).toList());
			// 7
			assertThat(deposits).extracting(Deposit::getPaymentId).doesNotHaveDuplicates();
		}

		/** 새로고침·네트워크 재시도가 정상 경로다. 두 번째 호출은 행을 하나도 더하지 않고 최초 본문을 돌려준다. */
		@Test
		@DisplayName("같은 paymentKey 재전송 — 재생이고 본문은 같고 행은 늘지 않는다")
		void replaysOnResend() {
			Long userId = newUserId();
			MockApproveRes approved = approve(userId, PaymentMethod.TRANSFER, AMOUNT);
			DepositConfirmOutcome first = depositService.confirm(userId, approved.paymentId(), approved.paymentKey(),
				AMOUNT);

			DepositConfirmOutcome second = depositService.confirm(userId, approved.paymentId(), approved.paymentKey(),
				AMOUNT);

			assertThat(second.replayed()).isTrue();
			assertThat(second.body()).isEqualTo(first.body());
			assertThat(account(userId).getCashBalance()).isEqualTo(AMOUNT);
			assertThat(ledgerEntryRepository.findByAccountIdOrderByIdDesc(account(userId).getId())).hasSize(1);
		}

		/**
		 * <b>이 테스트가 이 스토리의 필수 조건이다</b> (backend_story S3). 같은 키로 10건이 동시에 도착해도
		 * payment FOR UPDATE 가 줄을 세워 정확히 하나만 반영하고 나머지 아홉은 재생이다.
		 */
		@Test
		@DisplayName("동시 confirm 10건 → 반영 1건, 재생 9건, 예수금은 한 번만 는다")
		void concurrentConfirmsReflectOnce() throws Exception {
			Long userId = newUserId();
			MockApproveRes approved = approve(userId, PaymentMethod.TRANSFER, AMOUNT);
			int concurrency = 10;
			CountDownLatch start = new CountDownLatch(1);
			Callable<DepositConfirmOutcome> call = () -> {
				start.await();
				return depositService.confirm(userId, approved.paymentId(), approved.paymentKey(), AMOUNT);
			};

			List<DepositConfirmOutcome> outcomes = new ArrayList<>();
			try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
				// invokeAll 은 전부 끝날 때까지 막으므로 쓰지 않는다 — 래치를 풀 기회가 없어 교착된다.
				List<Future<DepositConfirmOutcome>> futures = new ArrayList<>();
				for (int i = 0; i < concurrency; i++) {
					futures.add(pool.submit(call));
				}
				// 열 스레드가 전부 래치 앞에 선 뒤 한 번에 출발시킨다.
				start.countDown();
				for (Future<DepositConfirmOutcome> future : futures) {
					outcomes.add(future.get());
				}
			}

			assertThat(outcomes).hasSize(concurrency);
			assertThat(outcomes.stream().filter(o -> !o.replayed())).hasSize(1);
			assertThat(outcomes).extracting(DepositConfirmOutcome::body).containsOnly(outcomes.getFirst().body());
			assertThat(account(userId).getCashBalance()).isEqualTo(AMOUNT);
			assertThat(account(userId).getTotalDepositedAmount()).isEqualTo(AMOUNT);
			assertThat(ledgerEntryRepository.findByAccountIdOrderByIdDesc(account(userId).getId())).hasSize(1);
		}

		@Test
		@DisplayName("판정 1 — 없는 건 · 남의 건 · 다른 키는 전부 DEPOSIT_NOT_FOUND")
		void notFoundCases() {
			Long owner = newUserId();
			Long other = newUserId();
			MockApproveRes approved = approve(owner, PaymentMethod.TRANSFER, AMOUNT);

			assertThatThrownBy(() -> depositService.confirm(owner, 999_999_999L, approved.paymentKey(), AMOUNT))
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_NOT_FOUND);
			assertThatThrownBy(() -> depositService.confirm(other, approved.paymentId(), approved.paymentKey(), AMOUNT))
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_NOT_FOUND);
			assertThatThrownBy(() -> depositService.confirm(owner, approved.paymentId(), "mock_pk_wrong", AMOUNT))
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_NOT_FOUND);

			// 세 번 거절돼도 건은 그대로 APPROVED 다 — 판정 1 은 건을 굳히지 않는다.
			assertThat(paymentRepository.findById(approved.paymentId()).orElseThrow().getStatus())
				.isEqualTo(PaymentStatus.APPROVED);
			assertThat(account(owner).getCashBalance()).isZero();
		}

		@Test
		@DisplayName("판정 2 — READY 는 DEPOSIT_NOT_APPROVED (키가 없으므로 키 판정을 건너뛴다)")
		void readyIsNotApproved() {
			Long userId = newUserId();
			Long paymentId = depositService.ready(userId, PaymentMethod.TRANSFER, AMOUNT).paymentId();

			assertThatThrownBy(() -> depositService.confirm(userId, paymentId, "mock_pk_anything", AMOUNT))
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_NOT_APPROVED);
		}

		@Test
		@DisplayName("판정 3 — FAILED 는 DEPOSIT_PAYMENT_FAILED")
		void failedIsRejected() {
			Long userId = newUserId();
			Long paymentId = depositService.ready(userId, PaymentMethod.TRANSFER, AMOUNT).paymentId();
			assertThatThrownBy(() -> depositService.mockApprove(userId, paymentId, MockScenario.TIMEOUT))
				.isInstanceOf(DepositRejectedException.class);

			assertThatThrownBy(() -> depositService.confirm(userId, paymentId, "mock_pk_anything", AMOUNT))
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_PAYMENT_FAILED);
		}

		@Test
		@DisplayName("판정 4 — 금액 불일치는 DEPOSIT_AMOUNT_MISMATCH 이고 그 건은 FAILED 로 굳는다. 예수금은 그대로다")
		void amountMismatchFreezesPayment() {
			Long userId = newUserId();
			MockApproveRes approved = approve(userId, PaymentMethod.TRANSFER, AMOUNT);

			assertThatThrownBy(() -> depositService.confirm(userId, approved.paymentId(), approved.paymentKey(), AMOUNT + 1))
				.isInstanceOf(DepositRejectedException.class)
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_AMOUNT_MISMATCH);

			Payment payment = paymentRepository.findById(approved.paymentId()).orElseThrow();
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
			assertThat(payment.getFailCode()).isEqualTo("AMOUNT_MISMATCH");
			assertThat(account(userId).getCashBalance()).isZero();

			// 굳은 건은 맞는 금액으로 다시 와도 실패다 (featureSpec 3.3 "처음부터 다시").
			assertThatThrownBy(() -> depositService.confirm(userId, approved.paymentId(), approved.paymentKey(), AMOUNT))
				.extracting("errorCode").isEqualTo(DepositErrorCode.DEPOSIT_PAYMENT_FAILED);
		}

		/**
		 * ready 의 사전 판정은 둘 다 통과시킨다(각각 잔여 한도 안). 먼저 확정된 쪽이 한도를 채우고,
		 * 나중 쪽은 confirm 의 최종 판정에서 막힌다 — "진실은 confirm 의 판정"(apiSpec 4.2).
		 */
		@Test
		@DisplayName("판정 5 — 누적 한도 초과는 DEPOSIT_LIMIT_EXCEEDED + remainingAmount 이고 그 건은 FAILED 로 굳는다")
		void limitExceededAtConfirmFreezesPayment() {
			Long userId = newUserId();
			depositUpTo(userId, properties.cumulativeLimit() - AMOUNT);
			MockApproveRes first = approve(userId, PaymentMethod.TRANSFER, AMOUNT);
			MockApproveRes second = approve(userId, PaymentMethod.TRANSFER, AMOUNT);

			depositService.confirm(userId, first.paymentId(), first.paymentKey(), AMOUNT);

			assertThatThrownBy(() -> depositService.confirm(userId, second.paymentId(), second.paymentKey(), AMOUNT))
				.isInstanceOf(DepositRejectedException.class)
				.satisfies(e -> {
					CustomException ex = (CustomException) e;
					assertThat(ex.getErrorCode()).isEqualTo(DepositErrorCode.DEPOSIT_LIMIT_EXCEEDED);
					assertThat(ex.getDetail()).hasFieldOrPropertyWithValue("remainingAmount", 0L);
				});

			Payment payment = paymentRepository.findById(second.paymentId()).orElseThrow();
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
			assertThat(payment.getFailCode()).isEqualTo("LIMIT_EXCEEDED");
			assertThat(account(userId).getTotalDepositedAmount()).isEqualTo(properties.cumulativeLimit());
		}

		/**
		 * "PG 승인 뒤 서버가 죽은" 복구 경로다. 승인은 기록됐는데 confirm 이 오지 않은 채 시간이 흐른 건이
		 * 나중에 확정을 요청받으면 반영되어야 한다. 만료({@code expires_at})는 READY 에만 적용된다 —
		 * PG 가 이미 승인한 결제를 우리 쪽 사정으로 버리지 않는다.
		 */
		@Test
		@DisplayName("복구 — APPROVED 건은 expires_at 이 지났어도 confirm 이 오면 반영된다")
		void approvedPaymentConfirmsAfterExpiry() {
			Long userId = newUserId();
			Long accountId = account(userId).getId();
			Payment stale = transactionTemplate.execute(status -> {
				Payment p = paymentRepository.save(
					Payment.ready(accountId, PaymentMethod.KAKAOPAY, AMOUNT, Instant.now().minus(Duration.ofHours(1))));
				p.approve("A-recovered-1", Instant.now().minus(Duration.ofMinutes(30)));
				return p;
			});
			expiryScheduler.expireBefore(Instant.now());

			DepositConfirmOutcome outcome = depositService.confirm(userId, stale.getId(), "A-recovered-1", AMOUNT);

			assertThat(outcome.replayed()).isFalse();
			assertThat(account(userId).getCashBalance()).isEqualTo(AMOUNT);
			assertThat(paymentRepository.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.DONE);
		}
	}

	@Nested
	@DisplayName("kakaoApproval (카카오 대행 모드)")
	class KakaoApproval {

		@Test
		@DisplayName("성공 — APPROVED 로 바꾸고 성공 URL 에 paymentId · paymentKey · amount 를 붙인다")
		void approvesAndRedirectsToSuccess() {
			Long userId = newUserId();
			Long paymentId = depositService.ready(userId, PaymentMethod.KAKAOPAY, AMOUNT).paymentId();

			String url = depositService.kakaoApproval(paymentId, "pg-token");

			Payment payment = paymentRepository.findById(paymentId).orElseThrow();
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
			assertThat(url).startsWith(properties.successUrl())
				.contains("paymentId=" + paymentId)
				.contains("paymentKey=" + payment.getPaymentKey())
				.contains("amount=" + AMOUNT);
			assertThat(account(userId).getCashBalance()).isZero();
		}

		/** 404 를 주지 않는다 — 그것 자체가 "그 번호는 존재한다"는 정보다 (apiSpec 4.3.1). */
		@Test
		@DisplayName("없는 건은 실패 URL 로 보내고 code 는 DEPOSIT_NOT_FOUND 다")
		void unknownPaymentRedirectsToFail() {
			String url = depositService.kakaoApproval(999_999_999L, "pg-token");

			assertThat(url).startsWith(properties.failUrl()).contains("code=DEPOSIT_NOT_FOUND");
		}

		@Test
		@DisplayName("TRANSFER 건이나 이미 승인된 건은 DEPOSIT_INVALID_STATE 로 실패 URL")
		void wrongStateRedirectsToFail() {
			Long userId = newUserId();
			Long transfer = depositService.ready(userId, PaymentMethod.TRANSFER, AMOUNT).paymentId();
			Long kakao = depositService.ready(userId, PaymentMethod.KAKAOPAY, AMOUNT).paymentId();
			depositService.kakaoApproval(kakao, "pg-token");

			assertThat(depositService.kakaoApproval(transfer, "pg-token")).contains("code=DEPOSIT_INVALID_STATE");
			assertThat(depositService.kakaoApproval(kakao, "pg-token")).contains("code=DEPOSIT_INVALID_STATE");
		}

		@Test
		@DisplayName("pg_token 이 없으면 DEPOSIT_PAYMENT_FAILED 로 실패 URL 이고 건은 READY 그대로다")
		void missingTokenRedirectsToFail() {
			Long userId = newUserId();
			Long paymentId = depositService.ready(userId, PaymentMethod.KAKAOPAY, AMOUNT).paymentId();

			assertThat(depositService.kakaoApproval(paymentId, " ")).contains("code=DEPOSIT_PAYMENT_FAILED");
			assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.READY);
		}
	}

	@Nested
	@DisplayName("getLimit · 만료 배치")
	class LimitAndExpiry {

		@Test
		@DisplayName("한도 조회는 누적 충전액과 잔여 한도를 계좌 스냅샷에서 계산한다")
		void limitReflectsDeposits() {
			Long userId = newUserId();
			MockApproveRes approved = approve(userId, PaymentMethod.TRANSFER, 3_000_000L);
			depositService.confirm(userId, approved.paymentId(), approved.paymentKey(), 3_000_000L);

			DepositLimitRes limit = depositService.getLimit(userId);

			assertThat(limit.perRequestLimit()).isEqualTo(properties.perRequestLimit());
			assertThat(limit.cumulativeLimit()).isEqualTo(properties.cumulativeLimit());
			assertThat(limit.depositedAmount()).isEqualTo(3_000_000L);
			assertThat(limit.remainingAmount()).isEqualTo(properties.cumulativeLimit() - 3_000_000L);
		}

		@Test
		@DisplayName("만료 배치는 expires_at 이 지난 READY 만 FAILED(EXPIRED) 로 바꾸고 APPROVED 는 건드리지 않는다")
		void expiresOnlyStaleReadyPayments() {
			Long userId = newUserId();
			Long freshReady = depositService.ready(userId, PaymentMethod.TRANSFER, AMOUNT).paymentId();
			MockApproveRes approved = approve(userId, PaymentMethod.TRANSFER, AMOUNT);
			Instant future = Instant.now().plus(properties.readyTtl()).plus(Duration.ofMinutes(1));

			int expired = expiryScheduler.expireBefore(future);

			Payment ready = paymentRepository.findById(freshReady).orElseThrow();
			assertThat(expired).isGreaterThanOrEqualTo(1);
			assertThat(ready.getStatus()).isEqualTo(PaymentStatus.FAILED);
			assertThat(ready.getFailCode()).isEqualTo("EXPIRED");
			assertThat(paymentRepository.findById(approved.paymentId()).orElseThrow().getStatus())
				.isEqualTo(PaymentStatus.APPROVED);
		}

		@Test
		@DisplayName("만료 전 READY 는 배치가 지나가도 그대로다")
		void keepsUnexpiredReady() {
			Long userId = newUserId();
			Long paymentId = depositService.ready(userId, PaymentMethod.TRANSFER, AMOUNT).paymentId();

			expiryScheduler.expireBefore(Instant.now());

			assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.READY);
		}
	}

	// ---- helpers ----

	private MockApproveRes approve(Long userId, PaymentMethod method, long amount) {
		Long paymentId = depositService.ready(userId, method, amount).paymentId();
		return depositService.mockApprove(userId, paymentId, MockScenario.SUCCESS);
	}

	/** 누적 충전액을 목표까지 채운다. 1회 한도 단위로 나눠 넣는다. */
	private void depositUpTo(Long userId, long target) {
		long remaining = target;
		while (remaining > 0) {
			long amount = Math.min(remaining, properties.perRequestLimit());
			MockApproveRes approved = approve(userId, PaymentMethod.TRANSFER, amount);
			depositService.confirm(userId, approved.paymentId(), approved.paymentKey(), amount);
			remaining -= amount;
		}
		assertThat(account(userId).getTotalDepositedAmount()).isEqualTo(target);
	}

	private Account account(Long userId) {
		return accountRepository.findByUserId(userId).orElseThrow();
	}

	private Long newUserId() {
		User user = userRepository.save(User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg"));
		accountService.ensureAccount(user.getId());
		return user.getId();
	}
}
