package com.finch.domain.withdrawal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.entity.Account;
import com.finch.domain.account.repository.AccountRepository;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.deposit.dto.response.DepositLimitRes;
import com.finch.domain.deposit.dto.response.MockApproveRes;
import com.finch.domain.deposit.entity.PaymentMethod;
import com.finch.domain.deposit.gateway.MockScenario;
import com.finch.domain.deposit.service.DepositService;
import com.finch.domain.ledger.entity.LedgerEntry;
import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.repository.LedgerEntryRepository;
import com.finch.domain.withdrawal.dto.response.WithdrawalRes;
import com.finch.domain.withdrawal.entity.Withdrawal;
import com.finch.domain.withdrawal.exception.WithdrawalErrorCode;
import com.finch.domain.withdrawal.repository.WithdrawalRepository;
import com.finch.global.exception.CustomException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/**
 * 출금이 실제 DB 에서 무엇을 남기는지 본다. 목으로는 볼 수 없는 것들이 대상이다 — FOR UPDATE 의 줄 세우기,
 * 불변식 1·6, 그리고 <b>누적 충전액이 그대로인 것</b>(이 스토리의 핵심 회귀 조건).
 * <p>
 * 예수금은 충전(S3)으로 만든다 — 초기 지급이 0 이라 계좌는 빈 채로 열리고, 잔고를 만드는 정상 경로가 충전뿐이다.
 * {@code finch.deposit.kakaopay.enabled=false}(테스트 설정) 라 모의 이체로 끝난다. 여기서 deposit 도메인을 부르는 것은
 * 테스트라서다 — 구현({@code WithdrawalService})은 deposit 을 참조하지 않는다 (backConvention 2.4).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WithdrawalServiceTest {

	private static final AtomicLong KAKAO_ID = new AtomicLong(920_000_000L);
	private static final long FUNDED = 1_000_000L;

	@Autowired
	private WithdrawalService withdrawalService;

	@Autowired
	private DepositService depositService;

	@Autowired
	private WithdrawalRepository withdrawalRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private UserRepository userRepository;

	@Nested
	@DisplayName("정상 출금")
	class Success {

		@Test
		@DisplayName("원장(WITHDRAWAL, cash_delta 음수)·withdrawal·계좌 세 곳이 한 트랜잭션에서 갱신된다")
		void reflectsWithdrawal() {
			Long userId = fundedUser(FUNDED);

			WithdrawalRes res = withdrawalService.withdraw(userId, 300_000L);

			assertThat(res.amount()).isEqualTo(300_000L);
			assertThat(res.cashBalanceAfter()).isEqualTo(700_000L);
			// apiSpec 1.1 — 시각은 KST 오프셋을 포함한다.
			assertThat(res.withdrawnAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);

			Account account = account(userId);
			assertThat(account.getCashBalance()).isEqualTo(700_000L);

			List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.getId());
			// 최신순이라 첫 행이 출금이다. 둘째는 충전.
			assertThat(entries).hasSize(2);
			LedgerEntry entry = entries.getFirst();
			assertThat(entry.getType()).isEqualTo(LedgerType.WITHDRAWAL);
			assertThat(entry.getCashDelta()).isEqualTo(-300_000L);
			assertThat(entry.getCashBalanceAfter()).isEqualTo(700_000L);

			Withdrawal withdrawal = withdrawalRepository.findById(res.withdrawalId()).orElseThrow();
			assertThat(withdrawal.getLedgerEntryId()).isEqualTo(entry.getId());
			assertThat(withdrawal.getAccountId()).isEqualTo(account.getId());
			// 상세는 양수 절대값이고 부호는 원장만 갖는다 (erd.md §2.13).
			assertThat(withdrawal.getAmount()).isEqualTo(300_000L);
			assertThat(withdrawal.getCreatedAt()).isEqualTo(entry.getOccurredAt());
			assertThat(res.withdrawnAt().toInstant()).isEqualTo(entry.getOccurredAt());
		}

		/**
		 * <b>이 스토리의 핵심 회귀 테스트다.</b> 출금이 누적 충전액을 되돌리면 충전↔출금 반복으로 누적 한도를
		 * 무한히 우회할 수 있다 (apiSpec 4.5). 계좌 스냅샷과 한도 조회 응답 둘 다 그대로여야 한다.
		 */
		@Test
		@DisplayName("total_deposited_amount 는 변하지 않고 GET /deposits/limit 의 depositedAmount·remainingAmount 도 그대로다")
		void doesNotRevertDepositLimit() {
			Long userId = fundedUser(FUNDED);
			DepositLimitRes before = depositService.getLimit(userId);

			withdrawalService.withdraw(userId, 600_000L);

			assertThat(account(userId).getTotalDepositedAmount()).isEqualTo(FUNDED);
			DepositLimitRes after = depositService.getLimit(userId);
			assertThat(after.depositedAmount()).isEqualTo(before.depositedAmount()).isEqualTo(FUNDED);
			assertThat(after.remainingAmount()).isEqualTo(before.remainingAmount());
		}

		@Test
		@DisplayName("잔고와 정확히 같은 금액은 성공하고 cash_balance = 0 이다 — 전액 출금")
		void exactBalanceIsAllowed() {
			Long userId = fundedUser(FUNDED);

			WithdrawalRes res = withdrawalService.withdraw(userId, FUNDED);

			assertThat(res.cashBalanceAfter()).isZero();
			assertThat(account(userId).getCashBalance()).isZero();
			// 전액 빼도 누적 충전액은 그대로다.
			assertThat(account(userId).getTotalDepositedAmount()).isEqualTo(FUNDED);
		}

		/** erd.md §4 — 원장과 스냅샷이 갈라지면 여기서 잡힌다. 충전 하나에 출금 셋을 섞은 뒤 대조한다. */
		@Test
		@DisplayName("불변식 1·6 — 잔고 = 원장 합, WITHDRAWAL 원장 1행 = withdrawal 1행")
		void satisfiesInvariants() {
			Long userId = fundedUser(FUNDED);
			for (long amount : new long[] {100_000L, 250_000L, 400_000L}) {
				withdrawalService.withdraw(userId, amount);
			}

			Account account = account(userId);
			List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.getId());
			List<Withdrawal> withdrawals = withdrawalRepository.findByAccountIdOrderByIdDesc(account.getId());

			// 1
			assertThat(account.getCashBalance())
				.isEqualTo(entries.stream().mapToLong(LedgerEntry::getCashDelta).sum())
				.isEqualTo(250_000L);
			// 6
			List<LedgerEntry> withdrawalEntries = entries.stream()
				.filter(e -> e.getType() == LedgerType.WITHDRAWAL).toList();
			assertThat(withdrawalEntries).hasSize(3);
			assertThat(withdrawals).extracting(Withdrawal::getLedgerEntryId)
				.containsExactlyInAnyOrderElementsOf(withdrawalEntries.stream().map(LedgerEntry::getId).toList());
			assertThat(withdrawalRepository.sumAmountByAccountId(account.getId())).isEqualTo(750_000L);
			// 2 — 출금은 불변식 2 를 건드리지 않는다.
			assertThat(account.getTotalDepositedAmount()).isEqualTo(FUNDED);
		}
	}

	@Nested
	@DisplayName("판정")
	class Rejection {

		@Test
		@DisplayName("판정 2 — 0·음수는 WITHDRAWAL_AMOUNT_INVALID 이고 아무 행도 남지 않는다")
		void rejectsNonPositiveAmount() {
			Long userId = fundedUser(FUNDED);

			for (long amount : new long[] {0L, -1L}) {
				assertThatThrownBy(() -> withdrawalService.withdraw(userId, amount))
					.isInstanceOf(CustomException.class)
					.extracting(e -> ((CustomException) e).getErrorCode())
					.isEqualTo(WithdrawalErrorCode.WITHDRAWAL_AMOUNT_INVALID);
			}
			assertThat(withdrawalRepository.findByAccountIdOrderByIdDesc(account(userId).getId())).isEmpty();
			assertThat(account(userId).getCashBalance()).isEqualTo(FUNDED);
		}

		@Test
		@DisplayName("판정 3 — 잔고 초과는 WITHDRAWAL_INSUFFICIENT_CASH 이고 detail.availableAmount 는 지금 잔고다")
		void rejectsOverBalance() {
			Long userId = fundedUser(FUNDED);

			assertThatThrownBy(() -> withdrawalService.withdraw(userId, FUNDED + 1))
				.isInstanceOf(CustomException.class)
				.satisfies(e -> {
					CustomException ce = (CustomException) e;
					assertThat(ce.getErrorCode()).isEqualTo(WithdrawalErrorCode.WITHDRAWAL_INSUFFICIENT_CASH);
					assertThat(ce.getDetail()).isEqualTo(Map.of("availableAmount", FUNDED));
				});
			assertThat(account(userId).getCashBalance()).isEqualTo(FUNDED);
			assertThat(ledgerEntryRepository.findByAccountIdOrderByIdDesc(account(userId).getId())).hasSize(1);
		}

		/** 계좌는 토큰의 사용자로만 찾는다 (apiSpec 1.6). 남의 잔고는 보이지도, 빠지지도 않는다. */
		@Test
		@DisplayName("남의 계좌에서는 뺄 수 없다 — 빈 계좌의 사용자는 다른 사용자의 잔고와 무관하게 부족 판정이다")
		void cannotWithdrawFromOthersAccount() {
			Long funded = fundedUser(FUNDED);
			Long empty = newUserId();

			assertThatThrownBy(() -> withdrawalService.withdraw(empty, 1L))
				.isInstanceOf(CustomException.class)
				.satisfies(e -> assertThat(((CustomException) e).getDetail()).isEqualTo(Map.of("availableAmount", 0L)));
			assertThat(account(funded).getCashBalance()).isEqualTo(FUNDED);
		}
	}

	/**
	 * <b>이 테스트가 이 스토리의 필수 조건이다</b> (backend_story S3.5). 잔고 100만에 60만 출금 셋이 동시에 와도
	 * 계좌 FOR UPDATE 가 줄을 세워 하나만 통과하고 나머지 둘은 잠근 값(40만)으로 부족 판정을 받는다.
	 * 락이 없으면 셋 다 100만을 읽어 통과하고 잔고가 -80만이 된다 — DB CHECK 가 막더라도 그건 마지막 방어선이지 설계가 아니다.
	 */
	@Test
	@DisplayName("동시 출금 — 100만 잔고에 60만 출금 3건 동시 → 성공 1건, 부족 2건, 잔고는 40만")
	void concurrentWithdrawalsSerialize() throws Exception {
		Long userId = fundedUser(FUNDED);
		int concurrency = 3;
		CountDownLatch start = new CountDownLatch(1);
		Callable<Outcome> call = () -> {
			start.await();
			try {
				return new Outcome(withdrawalService.withdraw(userId, 600_000L), null);
			} catch (CustomException e) {
				return new Outcome(null, e);
			}
		};

		List<Outcome> outcomes = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
			// invokeAll 은 전부 끝날 때까지 막으므로 쓰지 않는다 — 래치를 풀 기회가 없어 교착된다.
			List<Future<Outcome>> futures = new ArrayList<>();
			for (int i = 0; i < concurrency; i++) {
				futures.add(pool.submit(call));
			}
			start.countDown();
			for (Future<Outcome> future : futures) {
				outcomes.add(future.get());
			}
		}

		List<Outcome> succeeded = outcomes.stream().filter(o -> o.res() != null).toList();
		List<Outcome> rejected = outcomes.stream().filter(o -> o.error() != null).toList();
		assertThat(succeeded).hasSize(1);
		assertThat(succeeded.getFirst().res().cashBalanceAfter()).isEqualTo(400_000L);
		assertThat(rejected).hasSize(2)
			.allMatch(o -> o.error().getErrorCode() == WithdrawalErrorCode.WITHDRAWAL_INSUFFICIENT_CASH)
			// 잠근 뒤 읽은 잔고라 첫 출금이 반영된 값이다.
			.allMatch(o -> o.error().getDetail().equals(Map.of("availableAmount", 400_000L)));

		Account account = account(userId);
		assertThat(account.getCashBalance()).isEqualTo(400_000L).isNotNegative();
		assertThat(withdrawalRepository.findByAccountIdOrderByIdDesc(account.getId())).hasSize(1);
		assertThat(account.getTotalDepositedAmount()).isEqualTo(FUNDED);
	}

	private record Outcome(WithdrawalRes res, CustomException error) {
	}

	// ---- helpers ----

	/** 계좌를 열고 충전으로 예수금을 만든다. 100만 원 이하라 1회 한도 안이다. */
	private Long fundedUser(long amount) {
		Long userId = newUserId();
		Long paymentId = depositService.ready(userId, PaymentMethod.TRANSFER, amount).paymentId();
		MockApproveRes approved = depositService.mockApprove(userId, paymentId, MockScenario.SUCCESS);
		depositService.confirm(userId, approved.paymentId(), approved.paymentKey(), amount);
		assertThat(account(userId).getCashBalance()).isEqualTo(amount);
		return userId;
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
