package com.finch.domain.ledger.repository;

import java.time.Instant;

/**
 * {@link TransactionQueryRepository} 의 네이티브 쿼리 한 행. Spring Data 인터페이스 프로젝션이라 <b>SQL 의 별칭이
 * 곧 getter 이름</b>이다 — 별칭을 큰따옴표로 감싸야 Postgres 가 대소문자를 보존한다.
 * <p>
 * 엔티티가 아니다. 원장 행에 상세(trade·deposit·withdrawal)와 종목명을 LEFT JOIN 으로 붙인 <b>읽기 전용 결과</b>이고,
 * 유형에 따라 대부분의 컬럼이 null 이다. 어느 컬럼이 채워지는지는 원장 유형이 정한다 (erd.md §2.3 상세 테이블 표).
 * <p>
 * {@code occurredAt} 은 {@code Instant} 다 — Hibernate 가 네이티브 쿼리의 {@code TIMESTAMPTZ} 를 {@code Instant} 로
 * 돌려주고, 프로젝션 프록시는 타입을 바꿔 주지 않는다({@code OffsetDateTime} 으로 선언하면 "no matching Converter" 로
 * 죽는다). 저장은 UTC, 표기만 KST 라는 규약(backConvention 6장)과도 맞다. 응답 표기로의 변환은 {@code TransactionRes.from} 이 한다.
 */
public interface TransactionRow {

	/** apiSpec 8.2 의 {@code transactionId}. 커서도 이 값이다. */
	Long getId();

	/** 원장 유형 문자열. {@code LedgerType.valueOf} 로 읽는다 — DB CHECK 가 목록을 보장한다. */
	String getType();

	Instant getOccurredAt();

	/** 이하 trade 컬럼. BUY·SELL 에서만 채워진다. */
	String getStockCode();

	String getStockName();

	Long getPrice();

	Long getQuantity();

	/** SELL 에서만. 수익률의 분모 {@code avg_buy_price × quantity} 에 쓴다. */
	Long getAvgBuyPrice();

	Long getRealizedProfit();

	/** 유형별 금액의 양수 절대값. 어느 상세 테이블에서 왔는지는 SQL 의 COALESCE 가 정한다. */
	long getAmount();

	/** DEPOSIT 에서만. 결제수단 문자열 — ledger 는 deposit 의 enum 을 import 하지 않는다 (backConvention 2.4). */
	String getPaymentMethod();
}
