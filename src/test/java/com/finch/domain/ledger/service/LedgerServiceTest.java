package com.finch.domain.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.repository.AccountRepository;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.ledger.dto.response.LedgerEntryRes;
import com.finch.domain.ledger.entity.LedgerEntry;
import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.repository.LedgerEntryRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 원장이 <b>고쳐 쓸 수 없는 시계열</b>로 남는지 본다 (erd.md §2.3, backConvention 6장).
 * <p>
 * 여기서 고정하는 두 가지가 이후 모든 금액 스토리의 전제다 — 기록은 호출자의 트랜잭션에서만 일어나고,
 * 한 번 쓴 행은 바뀌지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerServiceTest {

	@Autowired
	private LedgerService ledgerService;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	/**
	 * <b>MANDATORY 의 존재 이유.</b> 원장 기록은 언제나 더 큰 사건(계좌 개설·충전·체결)의 일부이고,
	 * 그 사건과 같은 트랜잭션에서 커밋되거나 함께 롤백되어야 불변식 1 이 성립한다.
	 * REQUIRED 였다면 호출자가 트랜잭션을 빠뜨려도 <b>조용히</b> 원장 행만 남았을 것이다.
	 */
	@Test
	@DisplayName("트랜잭션 밖에서 기록하면 거부한다 — 원장만 남고 잔고가 갈라지는 것을 막는다")
	void rejectsRecordOutsideTransaction() {
		Long accountId = newAccountId();

		assertThatThrownBy(() ->
			ledgerService.record(accountId, LedgerType.DEPOSIT, 1000L, 1000L, Instant.now()))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	@DisplayName("호출자의 트랜잭션 안에서는 기록되고 결과 DTO 를 돌려준다")
	void recordsInsideTransaction() {
		Long accountId = newAccountId();

		LedgerEntryRes result = transactionTemplate.execute(status ->
			ledgerService.record(accountId, LedgerType.DEPOSIT, 50_000L, 50_000L, Instant.now()));

		assertThat(result).isNotNull();
		assertThat(result.id()).isNotNull();
		assertThat(result.type()).isEqualTo(LedgerType.DEPOSIT);
		assertThat(result.cashDelta()).isEqualTo(50_000L);
		assertThat(result.cashBalanceAfter()).isEqualTo(50_000L);
	}

	/**
	 * 호출자가 뒤에서 실패하면 원장 행도 함께 사라져야 한다. 남으면 "충전은 실패했는데 원장에는
	 * 들어간" 상태가 되고, 그때부터 잔고와 원장이 영구히 갈라진다.
	 */
	@Test
	@DisplayName("호출자 트랜잭션이 롤백되면 원장 행도 사라진다")
	void rollsBackWithCaller() {
		Long accountId = newAccountId();

		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
			ledgerService.record(accountId, LedgerType.DEPOSIT, 50_000L, 50_000L, Instant.now());
			throw new IllegalStateException("호출자가 뒤에서 실패했다");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(ledgerEntryRepository.findByAccountIdOrderByIdDesc(accountId)).isEmpty();
	}

	/**
	 * {@code @Immutable} 이라 Hibernate 가 이 엔티티의 변경 감지를 하지 않는다. 필드를 바꿔도 UPDATE 가
	 * 나가지 않는다 — 애초에 바꿀 메서드를 두지 않았으므로 이건 마지막 그물이다.
	 */
	@Test
	@DisplayName("원장 행은 한 번 쓰면 바뀌지 않는다 — 정정은 반대 분개로 한다")
	void entriesAreImmutable() {
		Long accountId = newAccountId();
		transactionTemplate.executeWithoutResult(status ->
			ledgerService.record(accountId, LedgerType.BUY, -70_000L, 30_000L, Instant.now()));

		LedgerEntry stored = ledgerEntryRepository.findByAccountIdOrderByIdDesc(accountId).getFirst();

		// 영속 상태에서 값을 바꾸고 트랜잭션을 닫아도 UPDATE 가 나가지 않는다.
		transactionTemplate.executeWithoutResult(status -> {
			LedgerEntry managed = ledgerEntryRepository.findById(stored.getId()).orElseThrow();
			org.springframework.test.util.ReflectionTestUtils.setField(managed, "cashDelta", 999L);
		});

		assertThat(ledgerEntryRepository.findById(stored.getId()).orElseThrow().getCashDelta())
			.isEqualTo(-70_000L);
	}

	@Test
	@DisplayName("매수는 음수, 매도·충전은 양수로 기록된다 (erd 2.3 부호 규약)")
	void keepsSignConvention() {
		Long accountId = newAccountId();

		transactionTemplate.executeWithoutResult(status -> {
			ledgerService.record(accountId, LedgerType.DEPOSIT, 100_000L, 100_000L, Instant.now());
			ledgerService.record(accountId, LedgerType.BUY, -70_000L, 30_000L, Instant.now());
			ledgerService.record(accountId, LedgerType.SELL, 75_000L, 105_000L, Instant.now());
		});

		List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByIdDesc(accountId);
		assertThat(entries).hasSize(3);
		// 합계가 마지막 행의 cash_balance_after 와 같아야 한다 (불변식 1 의 기초).
		assertThat(entries.stream().mapToLong(LedgerEntry::getCashDelta).sum()).isEqualTo(105_000L);
	}

	private Long newAccountId() {
		User user = userRepository.save(
			User.register(System.nanoTime(), "홍길동", "https://img.kakao/1.jpg"));
		accountService.ensureAccount(user.getId());
		return accountRepository.findByUserId(user.getId()).orElseThrow().getId();
	}
}
