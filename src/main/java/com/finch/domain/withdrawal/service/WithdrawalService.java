package com.finch.domain.withdrawal.service;

import com.finch.domain.account.dto.response.AccountBalanceRes;
import com.finch.domain.account.service.AccountService;
import com.finch.domain.ledger.dto.response.LedgerEntryRes;
import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.service.LedgerService;
import com.finch.domain.withdrawal.dto.response.WithdrawalRes;
import com.finch.domain.withdrawal.entity.Withdrawal;
import com.finch.domain.withdrawal.exception.WithdrawalErrorCode;
import com.finch.domain.withdrawal.repository.WithdrawalRepository;
import com.finch.global.exception.CustomException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예수금 출금 (apiSpec 4.5, featureSpec 3.4, erd.md §3.4). <b>예수금이 줄어드는 경로 둘 중 하나</b>다 (다른 하나는 매수).
 * <p>
 * 충전({@code DepositService.confirm})의 뒷부분과 대칭이고 <b>외부 호출이 없어 더 짧다.</b> 준비·승인 단계도, 상태
 * 머신도 없다 — 출금은 PG 를 거치지 않아 요청 한 번이 곧 확정이다. 그래서 트랜잭션이 하나이고 클래스 수준
 * {@code @Transactional} 로 충분하다.
 * <p>
 * <b>재전송은 이 서비스가 아니라 {@code IdempotencyFilter} 가 막는다.</b> 충전은 PG 가 발급한 {@code paymentKey} 를
 * 멱등 기준으로 쓰는데(결제창을 거쳐 돌아온 요청은 최초 호출과 다른 세션일 수 있어 클라이언트 키로 판정할 수 없다),
 * 출금은 PG 가 없어 키를 발급할 주체가 없다. 그래서 클라이언트가 만든 {@code Idempotency-Key}(apiSpec 1.4)로 돌아가고,
 * S0 이 만든 필터에 경로 한 줄({@code finch.idempotency.paths})을 더한 것이 전부다. 필터는 고치지 않았다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {

	private final WithdrawalRepository withdrawalRepository;
	private final AccountService accountService;
	private final LedgerService ledgerService;

	/**
	 * 출금. 판정 순서는 apiSpec 4.5 표 그대로다 — (멱등성은 필터가 앞에서) → 금액 0 이하 → 예수금 부족.
	 * <p>
	 * <b>한 트랜잭션</b>이고 순서가 곧 설계다:
	 * <ol>
	 *   <li>금액 판정 — 잠그기 전에 한다. 0 이하는 계좌를 볼 필요가 없고, 락은 짧을수록 좋다.</li>
	 *   <li>{@code account} FOR UPDATE ({@link AccountService#lockByUserId}) — 잔고의 진실은 잠근 값이다. 충전(S3)·
	 *       주문(S9)과 같은 행이라 셋이 서로 직렬화된다. <b>다른 락을 만들지 않는다</b> (S1 경계 메모).</li>
	 *   <li>{@code cash_balance >= amount} — 아니면 409 와 {@code detail.availableAmount}. 잔고와 <b>정확히 같은</b>
	 *       금액은 통과한다 (전액 출금).</li>
	 *   <li>원장 INSERT({@code WITHDRAWAL}, {@code cash_delta} 음수) → withdrawal INSERT → 계좌 스냅샷 UPDATE.
	 *       하나라도 실패하면 전부 롤백이다.</li>
	 * </ol>
	 * <b>{@code total_deposited_amount} 를 건드리지 않는다.</b> 출금은 충전 한도를 되돌리지 않는다 (apiSpec 4.5,
	 * erd.md §2.2). 되돌리면 충전↔출금 반복으로 누적 한도를 무한히 우회할 수 있고, 불변식 2(누적 충전액 =
	 * SUM(deposit.amount))도 깨진다. 그래서 {@link AccountService#applyWithdrawal} 은 잔고만 바꾼다.
	 * <p>
	 * 출금 가능액은 <b>예수금 전액</b>이다. "충전한 만큼만"({@code min(예수금, 누적충전 − 누적출금)})은 기각했다 — 한도
	 * 우회는 위 결정이 이미 막고 있어 그 규칙이 막아 주는 것이 없고, 규칙·계산·테스트만 하나 는다.
	 * <p>
	 * 계좌 식별자를 받지 않는다. 계좌는 토큰의 사용자로만 찾으므로(apiSpec 1.6) 남의 계좌에서 뺄 경로가 없다.
	 * DB 의 {@code cash_balance >= 0} CHECK 가 마지막 방어선이다 — 애플리케이션 검증을 통과한 버그를 바닥에서 막는다.
	 */
	@Transactional
	public WithdrawalRes withdraw(Long userId, long amount) {
		if (amount <= 0) {
			throw new CustomException(WithdrawalErrorCode.WITHDRAWAL_AMOUNT_INVALID);
		}

		AccountBalanceRes locked = accountService.lockByUserId(userId);
		if (locked.cashBalance() < amount) {
			throw new CustomException(WithdrawalErrorCode.WITHDRAWAL_INSUFFICIENT_CASH,
				Map.of("availableAmount", locked.cashBalance()));
		}

		Instant now = now();
		long cashBalanceAfter = locked.cashBalance() - amount;
		LedgerEntryRes entry = ledgerService.record(locked.accountId(), LedgerType.WITHDRAWAL, -amount,
			cashBalanceAfter, now);
		Withdrawal withdrawal = withdrawalRepository.save(
			Withdrawal.of(entry.id(), locked.accountId(), amount, now));
		accountService.applyWithdrawal(locked.accountId(), cashBalanceAfter);

		log.info("출금 withdrawalId={} amount={} cashBalanceAfter={}", withdrawal.getId(), amount, cashBalanceAfter);
		return WithdrawalRes.of(withdrawal.getId(), amount, cashBalanceAfter, now);
	}

	/**
	 * 이 서비스가 쓰는 "지금"이다. 마이크로초로 자른다 — 응답에 나가는 시각은 언제나 DB 에 저장된 값과 같아야 한다
	 * ({@code DepositService.now} 와 같은 이유. Postgres 는 마이크로초까지, 리눅스 JDK 는 나노초까지 준다).
	 * 출금 재전송은 필터가 최초 응답을 바이트 그대로 재생하므로 여기서 갈라질 일은 없지만, 내역 화면(S4)이 DB 에서
	 * 읽어 보여줄 {@code occurredAt} 과 이 응답의 {@code withdrawnAt} 이 같아야 한다.
	 */
	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}
}
