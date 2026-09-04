package com.finch.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.entity.Account;
import com.finch.domain.account.repository.AccountRepository;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.ledger.entity.LedgerEntry;
import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.repository.LedgerEntryRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>지급 정책이 설정값만으로 되살아나는지</b> 본다.
 * <p>
 * 초기 지급은 2026-09-04 에 제거됐지만(featureSpec 2.2) 코드를 지운 것이 아니라 지급액을 0 으로 둔 것이다.
 * 지급 여부는 <b>정책이지 구현이 아니라서</b>, 시연에서 잔고가 있는 상태로 시작해야 하면 값만 올릴 수
 * 있어야 한다. 그 경로가 살아 있는지 확인하지 않으면 <b>쓰지 않는 동안 조용히 썩는다</b> —
 * `INITIAL_GRANT` 유형과 `ck_ledger_type` CHECK 를 남겨 둔 것도 이 복원 가능성 때문이다.
 * <p>
 * 프로퍼티가 다르므로 {@code AccountServiceTest} 와 스프링 컨텍스트를 공유하지 않는다. 그래서 파일이
 * 따로다 — 같은 클래스에 넣으면 {@code @DirtiesContext} 로 매번 컨텍스트를 새로 띄워야 한다.
 */
@SpringBootTest(properties = "finch.account.initial-cash=1000000")
@Import(TestcontainersConfiguration.class)
class AccountServiceInitialGrantTest {

	private static final long INITIAL_CASH = 1_000_000L;

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Test
	@DisplayName("지급액을 올리면 계좌 잔고와 INITIAL_GRANT 원장 1행이 함께 돌아온다")
	void grantsInitialCashWhenConfigured() {
		Long userId = newUserId();

		transactionTemplate.executeWithoutResult(status -> accountService.openAccount(userId));

		Account account = accountRepository.findByUserId(userId).orElseThrow();
		assertThat(account.getCashBalance()).isEqualTo(INITIAL_CASH);

		List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.getId());
		assertThat(entries).hasSize(1);

		LedgerEntry grant = entries.getFirst();
		assertThat(grant.getType()).isEqualTo(LedgerType.INITIAL_GRANT);
		assertThat(grant.getCashDelta()).isEqualTo(INITIAL_CASH);
		// 계좌의 첫 사건이라 이전 잔고가 0 이다 — delta 와 after 가 같아야 불변식 1 이 성립한다.
		assertThat(grant.getCashBalanceAfter()).isEqualTo(INITIAL_CASH);
	}

	/** 지급이 있어도 누적 충전액은 0 이다 — 초기 지급은 충전이 아니다 (불변식 2). */
	@Test
	@DisplayName("초기 지급은 누적 충전액에 들어가지 않는다 — 충전 한도를 깎지 않는다")
	void grantDoesNotCountAsDeposit() {
		Long userId = newUserId();

		transactionTemplate.executeWithoutResult(status -> accountService.openAccount(userId));

		assertThat(accountRepository.findByUserId(userId).orElseThrow().getTotalDepositedAmount()).isZero();
	}

	private Long newUserId() {
		User user = userRepository.save(
			User.register(System.nanoTime(), "홍길동", "https://img.kakao/1.jpg"));
		return user.getId();
	}
}
