package com.finch.domain.account.service;

import com.finch.domain.account.AccountProperties;
import com.finch.domain.account.dto.response.AccountBalanceRes;
import com.finch.domain.account.dto.response.AccountRes;
import com.finch.domain.account.entity.Account;
import com.finch.domain.account.port.ValuationPort;
import com.finch.domain.account.repository.AccountRepository;
import com.finch.domain.auth.exception.AuthErrorCode;
import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.service.LedgerService;
import com.finch.global.exception.CustomException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 계좌를 소유하는 서비스. 다른 도메인은 `account` 테이블에 직접 닿지 않고 여기를 거친다
 * (backConvention 2.4 규칙 3).
 * <p>
 * 이 클래스가 하는 일이 셋이다 — 계좌를 열고, 요약을 읽고, <b>돈이 움직이는 트랜잭션에 락을 건다.</b>
 * 셋째가 이후 스토리(충전·출금·주문)의 전제라서 가장 중요하다.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

	private final AccountRepository accountRepository;
	private final LedgerService ledgerService;
	private final ValuationPort valuationPort;
	private final AccountProperties properties;

	/**
	 * 계좌를 열고 초기 예수금을 지급한다 (erd.md §3.1, featureSpec 2.2).
	 * <p>
	 * <b>{@code MANDATORY} 다.</b> 가입은 `users` INSERT 와 한 트랜잭션이어야 하고(erd.md §3.1),
	 * 그 트랜잭션은 호출자({@code UserRegistrationService})가 연다. 여기서 새로 열면 계정만 만들어지고
	 * 계좌가 없는 사용자가 생길 수 있다.
	 * <p>
	 * 순서는 <b>계좌 INSERT → 원장 기록</b>이다. 원장 행이 {@code account_id} 를 FK 로 들기 때문에
	 * 뒤집을 수 없다. 초기 지급은 {@code INITIAL_GRANT} 이고 기록 주체가 account 인 것은
	 * backConvention 2.5 가 정한 것이다.
	 * <p>
	 * <b>지급액이 0 이면 원장에 아무것도 남기지 않는다.</b> 기본 정책이 "지급 없음"이라 보통은 이쪽이다
	 * (featureSpec 2.2). {@code cash_delta = 0} 인 행을 남기지 않는 이유는 회차 전환 기록
	 * ({@code ROUND_OPEN}·{@code ROUND_CLOSE})을 없앤 이유와 같다 — <b>기록할 사건 자체가 없다</b>
	 * (erd.md §2.3). 잔고가 0 인 계좌는 원장도 비어 있고, 그래도 불변식 1
	 * ({@code cash_balance} = {@code SUM(cash_delta)})은 0 = 0 으로 성립한다.
	 * <p>
	 * 지급액이 있으면 {@code cashDelta} 와 {@code cashBalanceAfter} 가 같다 — 계좌의 첫 사건이라
	 * 이전 잔고가 0 이기 때문이다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void openAccount(Long userId) {
		long initialCash = properties.initialCash();
		Account account = accountRepository.save(Account.open(userId, initialCash));

		if (initialCash > 0) {
			ledgerService.record(account.getId(), LedgerType.INITIAL_GRANT, initialCash, initialCash, Instant.now());
		}
	}

	/**
	 * 계좌가 없으면 만든다. <b>로그인할 때마다</b> 부른다.
	 * <p>
	 * 왜 필요한가 — 계좌 도메인이 생기기 전에 로그인만 배포된 기간이 있어서 <b>계좌 없는 사용자</b>가
	 * 이미 존재한다. 그들에게 `GET /account` 는 404 이고 주문도 충전도 되지 않는다.
	 * <p>
	 * <b>마이그레이션 SQL 로 채우지 않는 이유</b> — 계좌 개설은 `account` INSERT 하나가 아니라
	 * "계좌 + {@code INITIAL_GRANT} 원장 1행"이다. SQL 로 만들면 그 규칙이 마이그레이션 파일과
	 * 애플리케이션 두 곳에 생기고, 초기 지급액이 바뀌면 둘이 갈라진다. 규칙은 한 곳에 있어야 한다.
	 * <p>
	 * 이미 있으면 아무 일도 하지 않는다 — 두 번째 로그인이 계좌를 다시 만들지 않는다.
	 * 동시 로그인으로 둘이 동시에 "없음"을 봐도 {@code uq_account_user} 가 둘째를 막는다.
	 */
	@Transactional
	public void ensureAccount(Long userId) {
		if (!accountRepository.existsByUserId(userId)) {
			// 같은 빈 안의 호출이라 프록시를 거치지 않는다 — openAccount 의 MANDATORY 는 여기서
			// 검사되지 않는다. 동작은 의도대로다(이 메서드의 트랜잭션 안에서 돈다). MANDATORY 가
			// 실제로 일하는 자리는 UserRegistrationService 가 프록시를 통해 부르는 쪽이다.
			openAccount(userId);
		}
	}

	/**
	 * 계좌 요약 (apiSpec 3.1). 평가금액은 {@link ValuationPort} 로 받는다 — 보유 종목은 portfolio 소유라
	 * account 가 직접 읽을 수 없다.
	 * <p>
	 * 시세 조회가 트랜잭션 안에 있는 것이 마음에 걸릴 수 있는데, 읽기 전용이고 포트 구현이 Redis 를
	 * 보기 때문에 DB 커넥션을 오래 붙잡지 않는다. 구현이 외부 HTTP 를 치게 되면 그때 밖으로 빼야 한다.
	 */
	@Transactional(readOnly = true)
	public AccountRes getSummary(Long userId) {
		Account account = accountRepository.findByUserId(userId)
			.orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_INVALID_TOKEN));

		ValuationPort.Valuation valuation = valuationPort.evaluate(account.getId());
		return AccountRes.of(account.getCashBalance(), valuation.evaluationAmount(), valuation.asOf());
	}

	/**
	 * 잠그지 않고 읽는 금액 스냅샷. 한도 조회·결제 준비의 <b>사전 판정</b>처럼 "지금 값"이면 충분한 곳에서 쓴다.
	 * <p>
	 * 돈이 움직이는 판정에는 쓰지 않는다 — 읽은 직후 다른 트랜잭션이 값을 바꿀 수 있다. 그런 판정은
	 * {@link #lockByUserId} 로 잠근 값으로 한다 (apiSpec 4.2 "진실은 confirm 의 판정").
	 */
	@Transactional(readOnly = true)
	public AccountBalanceRes getBalance(Long userId) {
		Account account = accountRepository.findByUserId(userId)
			.orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_INVALID_TOKEN));
		return AccountBalanceRes.from(account);
	}

	/**
	 * 계좌 행을 잠그고 가져온다. <b>이후 모든 금액 변경 트랜잭션의 직렬화 지점</b>이다 —
	 * 충전(S3)·출금·주문(S9)이 전부 이 메서드로 시작한다. <b>다른 락을 만들지 않는다.</b>
	 * <p>
	 * <b>반드시 트랜잭션 안에서 불러야 한다.</b> {@code SELECT ... FOR UPDATE} 는 트랜잭션이 끝나면
	 * 락이 풀린다. 트랜잭션 밖에서 부르면 Spring Data 가 메서드 하나짜리 트랜잭션을 열고 <b>바로
	 * 닫으므로</b> 락이 즉시 풀리는데, 호출자는 잠근 줄 알고 잔고를 검사한다 — 그 사이에 다른 요청이
	 * 끼어들어 예수금이 음수가 될 수 있다. 그런 코드는 테스트에서 <b>대부분 통과하고 부하가 걸릴 때만
	 * 틀린다.</b> 그래서 조용히 지나가지 않게 여기서 막는다.
	 * <p>
	 * <b>{@code @Transactional} 을 붙이지 않았다.</b> {@code MANDATORY} 로도 같은 것을 잡을 수 있지만
	 * 그러면 스프링이 먼저 {@code IllegalTransactionStateException("no existing transaction")} 을 던져
	 * <b>왜</b> 트랜잭션이 필요한지가 메시지에 남지 않는다. 이 규칙을 어기는 코드는 테스트를 통과하고
	 * 운영에서만 틀리므로, 그때 스택 트레이스만 보고도 이유를 알 수 있어야 한다.
	 * <p>
	 * 어노테이션이 없어도 동작은 같다 — 호출자의 트랜잭션에 그대로 참여하고, 트랜잭션이 없으면
	 * 아래 검사가 먼저 막는다.
	 */
	public Account lockByUserId(Long userId) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(
				"lockByUserId 는 트랜잭션 안에서만 부른다 — 밖에서 부르면 락이 즉시 풀려 잔고 검사가 무의미해진다");
		}
		return accountRepository.findByUserIdForUpdate(userId)
			.orElseThrow(() -> new CustomException(AuthErrorCode.AUTH_INVALID_TOKEN));
	}
}
