package com.finch.domain.ledger.dto.response;

import com.finch.domain.ledger.entity.LedgerType;
import com.finch.domain.ledger.repository.CashFlowRow;
import com.finch.global.util.KstTime;
import java.time.OffsetDateTime;

/**
 * 외부 현금흐름 한 건 — AI 내부 API 입출금 이력(apiSpec 9.3)의 한 행. 유형은 {@code INITIAL_GRANT}·{@code DEPOSIT}·{@code WITHDRAWAL}
 * 뿐이다. 매수·매도는 현금↔주식 내부 이동이라 수익률 분모에 들어가면 안 되고, 이미 9.2 로 나간다.
 * <p>
 * 화면용 {@link TransactionRes} 와 {@code amount} 의 의미가 다르다 — 거기는 양수 절대값이고 여기는 <b>부호 포함</b>이다.
 * AI 는 이 값을 외부 순입금 {@code F_t} 로 그대로 더한다.
 *
 * @param entryId          원장 행 id. 커서의 기준이다.
 * @param amount           {@code cash_delta}. 입금·지급 양수, 출금 음수.
 * @param cashBalanceAfter 그 사건 직후 예수금. AI 가 재생 결과와 대조하는 검산용이다.
 */
public record CashFlowRes(Long entryId, LedgerType type, long amount, long cashBalanceAfter, OffsetDateTime occurredAt) {

	public static CashFlowRes from(CashFlowRow row) {
		return new CashFlowRes(row.getId(), LedgerType.valueOf(row.getType()), row.getCashDelta(),
			row.getCashBalanceAfter(), KstTime.toResponse(row.getOccurredAt()));
	}
}
