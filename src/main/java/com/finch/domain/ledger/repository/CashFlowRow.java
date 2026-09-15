package com.finch.domain.ledger.repository;

import java.time.Instant;

/**
 * {@link TransactionQueryRepository#findCashFlowPage} 의 한 행 — 외부 현금흐름 원장 행 그대로다. 상세 테이블을 조인하지 않는다:
 * 필요한 값(유형·증감·직후 잔고·시각)이 전부 원장에 있다. 별칭·{@code Instant} 규칙은 {@link TransactionRow} 와 같다.
 */
public interface CashFlowRow {

	Long getId();

	/** 원장 유형 문자열. {@code LedgerType.valueOf} 로 읽는다. */
	String getType();

	/** {@code cash_delta} 그대로. 출금은 음수다. */
	long getCashDelta();

	long getCashBalanceAfter();

	Instant getOccurredAt();
}
