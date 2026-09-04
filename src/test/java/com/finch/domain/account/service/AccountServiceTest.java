package com.finch.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.dto.response.AccountRes;
import com.finch.domain.account.entity.Account;
import com.finch.domain.account.repository.AccountRepository;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.ledger.entity.LedgerEntry;
import com.finch.domain.ledger.repository.LedgerEntryRepository;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계좌 개설이 실제 DB 에서 무엇을 남기는지 본다. 목으로는 볼 수 없는 것들이 대상이다 —
 * 실제 INSERT 결과, 트랜잭션 전파, {@code SELECT ... FOR UPDATE}.
 * <p>
 * <b>초기 지급이 없는 것이 기본 정책이다</b> (featureSpec 2.2). 그래서 여기서 고정하는 것은
 * "계좌는 0원으로 열리고 원장은 비어 있다"이고, 지급이 되살아났을 때의 동작은
 * {@code AccountServiceInitialGrantTest} 가 따로 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountServiceTest {

	/** 같은 클래스의 테스트들이 서로 다른 kakaoId 를 쓰게 한다. 유니크 제약에 걸리면 원인이 엉뚱해 보인다. */
	private static final AtomicLong KAKAO_ID = new AtomicLong(900_000_000L);

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private UserRepository userRepository;

	/** {@code openAccount} 는 MANDATORY 라 호출자가 트랜잭션을 열어야 한다. 그 역할을 이 템플릿이 한다. */
	@Autowired
	private TransactionTemplate transactionTemplate;

	@Test
	@DisplayName("계좌는 0원으로 열리고 원장에는 아무 행도 남지 않는다")
	void opensAccountWithZeroCashAndNoLedgerRow() {
		Long userId = newUserId();

		transactionTemplate.executeWithoutResult(status -> accountService.openAccount(userId));

		Account account = accountRepository.findByUserId(userId).orElseThrow();
		assertThat(account.getCashBalance()).isZero();
		assertThat(account.getTotalDepositedAmount()).isZero();

		// cash_delta 가 0 인 행은 기록할 사건이 없다 (erd.md §2.3). 회차 전환 기록을 없앤 이유와 같다.
		assertThat(ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.getId())).isEmpty();
	}

	/**
	 * 불변식 1 (`account.cash_balance` = `SUM(ledger_entry.cash_delta)`) — erd.md §4.
	 * 원장이 비어 있어도 `0 = 0` 으로 성립해야 한다. 이 식이 깨지면 잔고와 원장이 갈라진 것이고,
	 * 그 상태는 이후 모든 금액 계산의 전제를 무너뜨린다.
	 */
	@Test
	@DisplayName("불변식 1 — 잔고는 원장 합계와 같다 (원장이 비어 있으면 0)")
	void satisfiesBalanceInvariantOnOpen() {
		Long userId = newUserId();

		transactionTemplate.executeWithoutResult(status -> accountService.openAccount(userId));

		Account account = accountRepository.findByUserId(userId).orElseThrow();
		long ledgerSum = ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.getId()).stream()
			.mapToLong(LedgerEntry::getCashDelta)
			.sum();

		assertThat(account.getCashBalance()).isEqualTo(ledgerSum);
	}

	/**
	 * 불변식 4 — 사용자별 계좌는 정확히 1개다. 계좌 도메인 이전에 가입한 사용자를 보정하는 경로가
	 * 로그인마다 도는데, 그것이 두 번째 계좌를 만들면 예수금이 갈라진다.
	 */
	@Test
	@DisplayName("ensureAccount 는 두 번 불러도 계좌를 하나만 만든다 (불변식 4)")
	void ensureAccountIsIdempotent() {
		Long userId = newUserId();

		accountService.ensureAccount(userId);
		Long firstAccountId = accountRepository.findByUserId(userId).orElseThrow().getId();

		accountService.ensureAccount(userId);

		// 두 번째 호출이 계좌를 다시 만들었다면 id 가 달라진다. 같은 id 면 그대로 둔 것이다.
		// "정확히 1개"는 uq_account_user 가 DB 에서 보장한다 — 여기서는 재생성이 없었음을 본다.
		assertThat(accountRepository.findByUserId(userId).orElseThrow().getId()).isEqualTo(firstAccountId);
	}

	@Test
	@DisplayName("ensureAccount 는 이미 계좌가 있으면 원장을 건드리지 않는다")
	void ensureAccountDoesNotTouchLedgerWhenAccountExists() {
		Long userId = newUserId();
		accountService.ensureAccount(userId);
		Account account = accountRepository.findByUserId(userId).orElseThrow();

		accountService.ensureAccount(userId);

		assertThat(ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.getId())).isEmpty();
	}

	/**
	 * <b>이 테스트가 지키는 것은 이후 모든 스토리다.</b> {@code lockByUserId} 는 충전·출금·주문 트랜잭션의
	 * 직렬화 지점인데, 트랜잭션 밖에서 부르면 락이 즉시 풀려 <b>잔고 검사가 무의미해진다.</b>
	 * 그런 코드는 단위 테스트를 통과하고 부하가 걸릴 때만 틀리므로, 호출 시점에 바로 막는다.
	 */
	@Test
	@DisplayName("lockByUserId 를 트랜잭션 밖에서 부르면 예외 — 락이 즉시 풀리는 것을 조용히 넘기지 않는다")
	void lockOutsideTransactionFails() {
		Long userId = newUserId();
		accountService.ensureAccount(userId);

		assertThatThrownBy(() -> accountService.lockByUserId(userId))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("트랜잭션 안에서만");
	}

	@Test
	@DisplayName("lockByUserId 는 트랜잭션 안에서는 계좌를 돌려준다")
	void lockInsideTransactionReturnsAccount() {
		Long userId = newUserId();
		accountService.ensureAccount(userId);

		Account locked = transactionTemplate.execute(status -> accountService.lockByUserId(userId));

		assertThat(locked).isNotNull();
		assertThat(locked.getUserId()).isEqualTo(userId);
	}

	@Test
	@DisplayName("계좌 요약은 예수금·평가금액·총자산을 서버가 계산해 내려준다")
	void summarizesAccount() {
		Long userId = newUserId();
		accountService.ensureAccount(userId);

		AccountRes summary = accountService.getSummary(userId);

		assertThat(summary.cashBalance()).isZero();
		// portfolio 가 붙기 전이라 기본 포트가 0 을 준다 (EmptyValuationPort).
		assertThat(summary.evaluationAmount()).isZero();
		assertThat(summary.totalAsset()).isZero();
		// apiSpec 1.1 — 시각은 KST 오프셋을 포함한다. Z 로 나가면 프론트 표시가 9시간 밀린다.
		assertThat(summary.asOf().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
	}

	@Test
	@DisplayName("계좌가 없는 사용자의 요약 조회는 AUTH_INVALID_TOKEN — 세션을 버리고 다시 로그인하라는 뜻이다")
	void summaryFailsWhenAccountMissing() {
		Long userId = newUserId();

		assertThatThrownBy(() -> accountService.getSummary(userId))
			.extracting("errorCode")
			.hasToString("AUTH_INVALID_TOKEN");
	}

	/** 계좌는 `users` 를 FK 로 물기 때문에 사용자 행이 먼저 있어야 한다. */
	private Long newUserId() {
		User user = userRepository.save(
			User.register(KAKAO_ID.incrementAndGet(), "홍길동", "https://img.kakao/1.jpg"));
		return user.getId();
	}
}
