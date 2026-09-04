package com.finch.domain.ledger.dto.response;

import com.finch.domain.ledger.entity.LedgerEntry;
import com.finch.domain.ledger.entity.LedgerType;
import java.time.Instant;

/**
 * 원장 기록의 결과. {@code LedgerService.record} 가 돌려준다.
 * <p>
 * 엔티티를 그대로 돌려주지 않는 이유 — 원장을 기록하는 도메인(account·deposit·order)은 전부
 * {@code ledger} 밖이고, 다른 도메인의 Entity 를 import 하지 않는 것이 규칙이다
 * (backConvention 2.4 규칙 3). 엔티티를 넘기면 호출자가 영속 객체를 손에 쥐게 되어
 * <b>불변이어야 할 원장을 고칠 수 있는 통로</b>가 생긴다.
 *
 * @param id               apiSpec 8.2 의 {@code transactionId}. 상세 테이블(deposit·trade)이 1:1 로 물린다.
 * @param cashBalanceAfter 기록 직후 예수금. 호출자가 계좌 스냅샷을 갱신할 때 그대로 쓴다.
 */
public record LedgerEntryRes(Long id, LedgerType type, long cashDelta, long cashBalanceAfter, Instant occurredAt) {

	public static LedgerEntryRes from(LedgerEntry entry) {
		return new LedgerEntryRes(entry.getId(), entry.getType(), entry.getCashDelta(),
			entry.getCashBalanceAfter(), entry.getOccurredAt());
	}
}
