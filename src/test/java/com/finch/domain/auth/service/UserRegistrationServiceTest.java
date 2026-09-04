package com.finch.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finch.TestcontainersConfiguration;
import com.finch.domain.account.repository.AccountRepository;
import com.finch.domain.auth.entity.User;
import com.finch.domain.auth.repository.UserRepository;
import com.finch.domain.ledger.repository.LedgerEntryRepository;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 가입 한 건이 <b>실제 DB 에서</b> 무엇을 남기고, 실패하면 무엇이 함께 사라지는지 본다 (erd.md §3.1).
 * <p>
 * 이 트랜잭션이 나뉘면 <b>계정만 있고 계좌가 없는 사용자</b>가 생긴다. 그런 사용자는 로그인은 되는데
 * 잔고 조회·충전·주문이 전부 실패하고, 원인이 가입 시점에 있어 뒤늦게 발견된다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserRegistrationServiceTest {

	@Autowired
	private UserRegistrationService userRegistrationService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Test
	@DisplayName("가입은 users 와 account 를 함께 만든다 — 원장은 초기 지급이 없어 비어 있다")
	void createsUserAndAccountTogether() {
		User user = userRegistrationService.register(kakaoUser(System.nanoTime()));

		assertThat(userRepository.findById(user.getId())).isPresent();

		var account = accountRepository.findByUserId(user.getId());
		assertThat(account).isPresent();
		assertThat(account.orElseThrow().getCashBalance()).isZero();
		assertThat(ledgerEntryRepository.findByAccountIdOrderByIdDesc(account.orElseThrow().getId())).isEmpty();
	}

	/**
	 * 같은 kakaoId 로 두 요청이 동시에 들어오는 경우다. `uq_users_kakao_id` 가 둘째를 막고,
	 * <b>진 쪽은 users 와 account 가 함께 롤백된다.</b>
	 * <p>
	 * 계좌만 남는 일이 없어야 한다 — 그러면 주인 없는 계좌가 생기고 불변식 4 를 셀 때 걸린다.
	 */
	@Test
	@DisplayName("동시 가입에서 진 쪽은 계정과 계좌가 함께 롤백된다 — 계좌만 남지 않는다")
	void rollsBackBothOnRaceLoss() throws Exception {
		long kakaoId = System.nanoTime();
		int concurrency = 2;

		List<Future<Object>> results;
		try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
			Callable<Object> register = () -> {
				try {
					return userRegistrationService.register(kakaoUser(kakaoId));
				} catch (DataIntegrityViolationException e) {
					return e;
				}
			};
			results = pool.invokeAll(java.util.Collections.nCopies(concurrency, register));
		}

		long created = results.stream().map(UserRegistrationServiceTest::value).filter(User.class::isInstance).count();
		assertThat(created).isEqualTo(1);

		// 계정이 하나이므로 계좌도 하나여야 한다 (불변식 4).
		User survivor = userRepository.findByKakaoId(kakaoId).orElseThrow();
		assertThat(accountRepository.findByUserId(survivor.getId())).isPresent();
		assertThat(accountRepository.count()).isPositive();
	}

	/**
	 * 계좌 개설이 실패하면 계정도 남지 않아야 한다. 같은 사용자로 두 번 가입을 시도해 두 번째에서
	 * {@code uq_account_user} 를 건드리게 만든다 — 계좌 INSERT 단계의 실패다.
	 */
	@Test
	@DisplayName("계좌 개설이 실패하면 계정도 롤백된다 — 계정만 남지 않는다")
	void rollsBackUserWhenAccountFails() {
		long kakaoId = System.nanoTime();
		User first = userRegistrationService.register(kakaoUser(kakaoId));
		long usersBefore = userRepository.count();

		// 같은 kakaoId 로 다시 부르면 users 의 유니크 제약에서 먼저 막힌다.
		assertThatThrownBy(() -> userRegistrationService.register(kakaoUser(kakaoId)))
			.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(userRepository.count()).isEqualTo(usersBefore);
		assertThat(userRepository.findByKakaoId(kakaoId).orElseThrow().getId()).isEqualTo(first.getId());
	}

	private static KakaoUser kakaoUser(long kakaoId) {
		return new KakaoUser(kakaoId, "홍길동", "https://img.kakao/1.jpg");
	}

	private static Object value(Future<Object> future) {
		try {
			return future.get();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
