package com.finch.domain.ledger.entity;

/**
 * 원장에 기록되는 사건의 종류 (erd.md §2.3).
 * <p>
 * <b>이 enum 은 화면 필터가 아니다.</b> `GET /transactions` 의 `type` 파라미터는 `ALL`·`BUY`·`SELL`·
 * `DEPOSIT` 네 값인데(apiSpec 8.2) `ALL` 은 원장 유형이 아니고 `INITIAL_GRANT` 를 가리키는 필터 값은
 * 없다. 축이 다르므로 필터 enum 은 따로 만든다 — 하나로 합치면 "필터에는 있는데 기록되지 않는 값"과
 * "기록되는데 필터에 없는 값"이 한 타입 안에 섞인다.
 * <p>
 * 값 이름은 DB 의 {@code ck_ledger_type} CHECK 목록과 문자 그대로 같아야 한다. 여기서 이름을 바꾸면
 * 마이그레이션 없이는 INSERT 가 제약 위반으로 실패한다.
 */
public enum LedgerType {

	/** 계좌 개설 시 1회. 기록 주체는 account 다 (backConvention 2.5). */
	INITIAL_GRANT,

	/** 충전. 기록 주체는 deposit 이다. */
	DEPOSIT,

	/** 매수 체결. {@code cash_delta} 가 음수다. 기록 주체는 order 다. */
	BUY,

	/** 매도 체결. {@code cash_delta} 가 양수다. 기록 주체는 order 다. */
	SELL
}
