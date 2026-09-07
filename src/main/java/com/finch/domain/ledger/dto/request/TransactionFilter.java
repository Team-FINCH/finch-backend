package com.finch.domain.ledger.dto.request;

import com.finch.domain.ledger.entity.LedgerType;

/**
 * `GET /transactions` 의 {@code type} 파라미터 (apiSpec 8.2). <b>화면 필터 축</b>이지 원장 유형이 아니다.
 * <p>
 * {@link LedgerType} 과 합치지 않는 이유 — {@code ALL} 은 필터에만 있고 {@code INITIAL_GRANT} 는 원장에만 있다.
 * 한 enum 에 넣으면 "필터에는 있는데 기록되지 않는 값"과 "기록되는데 필터에 없는 값"이 한 타입 안에 섞이고,
 * 프론트 분기 표에 {@code INITIAL_GRANT} 가 필터 값으로 새어 나간다.
 * <p>
 * {@code DEPOSIT} 은 원장 {@code DEPOSIT} 만이다 — 초기 지급도 출금도 아니다. 이 필터의 합계가 `GET /deposits/limit`
 * 의 {@code depositedAmount} 와 같아야 한다 (featureSpec 8). {@code WITHDRAWAL} 도 원장 {@code WITHDRAWAL} 만이다.
 * <p>
 * 열거값 밖의 문자열은 Spring 의 파라미터 변환이 {@code INVALID_REQUEST} 로 끊는다 (apiSpec 11.1).
 */
public enum TransactionFilter {

	ALL(null),
	BUY(LedgerType.BUY),
	SELL(LedgerType.SELL),
	DEPOSIT(LedgerType.DEPOSIT),
	WITHDRAWAL(LedgerType.WITHDRAWAL);

	private final LedgerType ledgerType;

	TransactionFilter(LedgerType ledgerType) {
		this.ledgerType = ledgerType;
	}

	/** 이 필터가 가리키는 원장 유형. {@code ALL} 은 없다 — 유형 조건 자체를 걸지 않는다. */
	public LedgerType ledgerType() {
		return ledgerType;
	}
}
