package com.finch.domain.ledger.service;

import com.finch.domain.ledger.dto.response.LedgerEntryRes;
import com.finch.domain.ledger.entity.LedgerEntry;
import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.repository.LedgerEntryRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원장에 행을 남기는 <b>유일한 창구</b>다 (backConvention 2.5).
 * <p>
 * `ledger` 는 1층(피참조 전용)이라 다른 도메인을 참조하지 않는다. 그래서 "얼마를 기록할지"는 전부
 * 호출자가 정해서 넘긴다 — 이 서비스는 계좌를 읽지도, 잔고를 계산하지도 않는다. 유형별 기록 주체는
 * account(`INITIAL_GRANT`) · deposit(`DEPOSIT`) · order(`BUY`·`SELL`) 로 고정돼 있다.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

	private final LedgerEntryRepository ledgerEntryRepository;

	/**
	 * 원장 한 줄을 남긴다.
	 * <p>
	 * <b>{@code MANDATORY} 다 — 자기 트랜잭션을 열지 않는다.</b> 원장 기록은 언제나 더 큰 사건의
	 * 일부이고(계좌 개설, 충전 반영, 주문 체결), 그 사건과 <b>같은 트랜잭션에서 커밋되거나 함께
	 * 롤백되어야</b> 불변식 1(`cash_balance` = `SUM(cash_delta)`)이 성립한다. 여기서 새 트랜잭션을
	 * 열면 호출자가 뒤에서 실패해도 원장 행만 남아 잔고와 원장이 영구히 갈라진다.
	 * <p>
	 * {@code REQUIRED} 로 두면 호출자가 트랜잭션을 깜빡했을 때 <b>조용히</b> 그 상태가 되므로,
	 * 아예 예외로 드러나게 한다.
	 *
	 * @param cashBalanceAfter 기록 직후 예수금. 계산은 호출자의 몫이다 — 이 서비스는 계좌를 읽지 않는다.
	 * @param occurredAt       사건이 일어난 시각. 호출자가 정해서 넘긴다. 한 트랜잭션에서 여러 행을
	 *                         남길 때(주문 체결) 같은 값을 쓰려면 호출자가 시각을 쥐고 있어야 한다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public LedgerEntryRes record(Long accountId, LedgerType type, long cashDelta, long cashBalanceAfter,
		Instant occurredAt) {
		LedgerEntry saved = ledgerEntryRepository.save(
			LedgerEntry.record(accountId, type, cashDelta, cashBalanceAfter, occurredAt));
		return LedgerEntryRes.from(saved);
	}
}
